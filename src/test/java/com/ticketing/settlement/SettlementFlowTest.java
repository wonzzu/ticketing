package com.ticketing.settlement;

import com.ticketing.event.domain.AgeLimit;
import com.ticketing.event.domain.Category;
import com.ticketing.event.domain.Event;
import com.ticketing.event.domain.EventSchedule;
import com.ticketing.global.entity.Address;
import com.ticketing.member.domain.Gender;
import com.ticketing.member.domain.NormalMember;
import com.ticketing.member.domain.Seller;
import com.ticketing.member.domain.SellerGrade;
import com.ticketing.payment.domain.Payment;
import com.ticketing.reservation.domain.CancelReason;
import com.ticketing.reservation.domain.Reservation;
import com.ticketing.reservation.service.ReservationService;
import com.ticketing.settlement.batch.SettlementScheduler;
import com.ticketing.settlement.domain.Settlement;
import com.ticketing.settlement.domain.SettlementDetail;
import com.ticketing.settlement.domain.SettlementDirtyDate;
import com.ticketing.settlement.repository.SettlementDetailRepository;
import com.ticketing.settlement.repository.SettlementDirtyDateRepository;
import com.ticketing.settlement.repository.SettlementRepository;
import com.ticketing.settlement.service.SettlementDirtyService;
import com.ticketing.venue.domain.Venue;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** 정산 배치 — 명세 생성 / 스냅샷 / 취소 반영 / 재집계 / 멱등. */
@SpringBootTest
@ActiveProfiles("test")
@Sql(scripts = "/truncate.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
@DisplayName("정산 - 배치")
class SettlementFlowTest {

    @Autowired SettlementScheduler settlementScheduler;
    @Autowired SettlementRepository settlementRepository;
    @Autowired SettlementDetailRepository settlementDetailRepository;
    @Autowired SettlementDirtyDateRepository settlementDirtyDateRepository;
    @Autowired SettlementDirtyService settlementDirtyService;
    @Autowired ReservationService reservationService;
    @Autowired TransactionTemplate tx;
    @PersistenceContext EntityManager em;

    private static final int PRICE = 100_000;

    private final LocalDate settlementDate = LocalDate.now().minusDays(1);
    private final int rate = SellerGrade.BRONZE.getCommissionPercent();

    private Long memberId;
    private Long canceledReservationId;

    @BeforeEach
    void setup() {
        tx.execute(status -> {
            Seller seller = Seller.create("settle-seller@t.com", "pw", "셀러", "010-0000-0000",
                    new Address("서울", "로1", "00000"), "컴퍼니", "대표", "000-00-00001");
            em.persist(seller);

            NormalMember member = NormalMember.create("settle@t.com", "pw", "회원", "닉",
                    LocalDate.of(1990, 1, 1), Gender.MALE, "010-0000-0001",
                    new Address("서울", "로2", "00000"));
            em.persist(member);

            Venue venue = Venue.create("정산홀", new Address("서울", "로3", "00000"), 1, 1);
            em.persist(venue);

            Event event = Event.create("정산공연", "설명", settlementDate.minusDays(3), settlementDate,
                    120, "출연", AgeLimit.ALL, Category.CONCERT, "url", seller);
            em.persist(event);

            EventSchedule schedule = EventSchedule.create(venue, settlementDate.atTime(19, 0), 1);
            event.addSchedule(schedule);
            em.persist(schedule);

            canceledReservationId = persistPaidReservation(member, schedule, "idem-settle-1");
            persistPaidReservation(member, schedule, "idem-settle-2");

            memberId = member.getId();
            em.flush();
            return null;
        });
    }

    private Long persistPaidReservation(NormalMember member, EventSchedule schedule, String idempotencyKey) {
        Reservation reservation = Reservation.create(member, schedule, idempotencyKey, PRICE);
        reservation.confirm();
        em.persist(reservation);
        em.persist(Payment.paid(reservation, PRICE));
        return reservation.getId();
    }

    private void runSettlementBatch() {
        assertThat(settlementScheduler.run(settlementDate)).isTrue();
    }

    private void cancelOnePayment() {
        reservationService.cancel(canceledReservationId, memberId, CancelReason.CHANGE_OF_MIND, null);
    }

    private void markSettlementDirty() {
        Settlement settlement = settlementRepository.findAll().get(0);
        settlementDirtyService.markDirtyIfSettled(
                settlement.getSellerId(), settlement.getEventId(), settlement.getSettlementDate());
    }

    @Test
    @DisplayName("배치 실행 시 건별 명세를 만들고 집계한다")
    void 명세를_생성하고_집계한다() {
        // given
        long gross = PRICE * 2L;

        // when
        runSettlementBatch();

        // then
        assertThat(settlementDetailRepository.count()).isEqualTo(2);
        assertThat(settlementRepository.count()).isEqualTo(1);

        Settlement settlement = settlementRepository.findAll().get(0);
        assertThat(settlement.getGrossAmount()).isEqualTo(gross);
        assertThat(settlement.getCommission()).isEqualTo(gross * rate / 100);
        assertThat(settlement.getNetAmount()).isEqualTo(gross - gross * rate / 100);

        SettlementDetail detail = settlementDetailRepository.findAll().get(0);
        assertThat(detail.getPaymentId()).isNotNull();
        assertThat(detail.getReservationId()).isNotNull();
        assertThat(detail.getSellerId()).isEqualTo(settlement.getSellerId());
        assertThat(detail.getEventId()).isEqualTo(settlement.getEventId());
        assertThat(detail.getSettlementDate()).isEqualTo(settlementDate);
        assertThat(detail.getGrossAmount()).isEqualTo((long) PRICE);
    }

    @Test
    @DisplayName("명세에는 정산 시점의 등급·수수료율·결제시각이 스냅샷으로 남는다")
    void 정산_시점_정보를_스냅샷으로_남긴다() {
        // given
        runSettlementBatch();

        // when
        SettlementDetail detail = settlementDetailRepository.findAll().get(0);

        // then
        assertThat(detail.getAppliedGrade()).isEqualTo(SellerGrade.BRONZE);
        assertThat(detail.getCommissionRate()).isEqualTo(rate);
        assertThat(detail.getCommission()).isEqualTo((long) PRICE * rate / 100);
        assertThat(detail.getPaidAt()).isNotNull();
    }

    @Test
    @DisplayName("정산 후 취소된 공연을 재집계 대기열에 적재할 수 있다")
    void 정산_후_취소를_대기열에_적재한다() {
        // given
        runSettlementBatch();
        Long settledEventId = settlementRepository.findAll().get(0).getEventId();

        // when
        cancelOnePayment();
        markSettlementDirty();

        // then
        assertThat(settlementDirtyDateRepository.count()).isEqualTo(1);

        SettlementDirtyDate dirty = settlementDirtyDateRepository.findAll().get(0);
        assertThat(dirty.getEventId()).isEqualTo(settledEventId);
        assertThat(dirty.getSettlementDate()).isEqualTo(settlementDate);
    }

    @Test
    @DisplayName("재집계하면 취소분이 정산에서 제외되고 대기열이 비워진다")
    void 재집계로_취소분을_제외한다() {
        // given
        runSettlementBatch();
        cancelOnePayment();
        markSettlementDirty();

        // when
        settlementScheduler.reaggregateDirty();

        // then
        assertThat(settlementDetailRepository.count()).isEqualTo(1);
        assertThat(settlementRepository.count()).isEqualTo(1);

        Settlement reaggregated = settlementRepository.findAll().get(0);
        assertThat(reaggregated.getGrossAmount()).isEqualTo((long) PRICE);
        assertThat(reaggregated.getNetAmount()).isEqualTo(PRICE - (long) PRICE * rate / 100);
        assertThat(settlementDirtyDateRepository.count()).isZero();
    }

    /** UPDATE 누적 방식이었다면 3배로 뻥튀기됐을 것 — DELETE&INSERT 멱등의 회귀를 잡는다. */
    @Test
    @DisplayName("배치를 여러 번 실행해도 명세·집계가 중복되거나 누적되지 않는다")
    void 여러_번_실행해도_결과가_같다() {
        // given
        runSettlementBatch();

        // when
        runSettlementBatch();
        runSettlementBatch();

        // then
        assertThat(settlementDetailRepository.count()).isEqualTo(2);
        assertThat(settlementRepository.count()).isEqualTo(1);
        assertThat(settlementRepository.findAll().get(0).getGrossAmount()).isEqualTo(PRICE * 2L);
    }
}
