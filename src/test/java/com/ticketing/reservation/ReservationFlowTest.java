package com.ticketing.reservation;

import com.ticketing.event.domain.AgeLimit;
import com.ticketing.event.domain.Category;
import com.ticketing.event.domain.Event;
import com.ticketing.event.domain.EventSchedule;
import com.ticketing.event.domain.EventSeat;
import com.ticketing.event.domain.EventSeatStatus;
import com.ticketing.event.repository.EventSeatRepository;
import com.ticketing.global.entity.Address;
import com.ticketing.member.domain.Gender;
import com.ticketing.member.domain.NormalMember;
import com.ticketing.member.domain.Seller;
import com.ticketing.payment.domain.PaymentStatus;
import com.ticketing.payment.dto.request.PaymentCreateDto;
import com.ticketing.payment.repository.PaymentRepository;
import com.ticketing.payment.service.PaymentService;
import com.ticketing.reservation.domain.CancelReason;
import com.ticketing.reservation.domain.Reservation;
import com.ticketing.reservation.domain.ReservationStatus;
import com.ticketing.reservation.dto.request.ReservationCreateDto;
import com.ticketing.reservation.dto.response.ReservationResponseDto;
import com.ticketing.reservation.repository.ReservationRepository;
import com.ticketing.reservation.service.ReservationService;
import com.ticketing.venue.domain.Seat;
import com.ticketing.venue.domain.SeatGrade;
import com.ticketing.venue.domain.Venue;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

/**
 * 기능 테스트 (안전망) — 예약·결제·취소 happy path.
 * 성능 개선(인덱스·fetch join 등) 후에도 핵심 흐름이 깨지지 않는지 검증.
 * 취소 검증으로 예매↔결제 cancel 위임 리팩토링도 함께 보장.
 */
@SpringBootTest
@ActiveProfiles("test")   // 공통 테스트 설정 — ticketing_test DB + DataSeedRunner 비활성화
@Sql(scripts = "/truncate.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)   // DB 정리
@DisplayName("기능 - 예약·결제·취소 happy path")
class ReservationFlowTest {

    @Autowired ReservationService reservationService;
    @Autowired PaymentService paymentService;
    @Autowired ReservationRepository reservationRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired EventSeatRepository eventSeatRepository;
    @Autowired StringRedisTemplate redis;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @PersistenceContext EntityManager em;

    private Long memberId;
    private Long scheduleId;
    private Long eventSeatId;

    @BeforeEach
    void setup() {
        tx.execute(s -> {
            Seller seller = Seller.create("flow-seller@t.com", "pw", "셀러", "010-0000-0000",
                    new Address("서울", "로1", "00000"), "컴퍼니", "대표", "000-00-00001");
            em.persist(seller);

            NormalMember member = NormalMember.create("flow@t.com", "pw", "회원", "닉",
                    LocalDate.of(1990, 1, 1), Gender.MALE, "010-0000-0001", new Address("서울", "로2", "00000"));
            em.persist(member);

            Venue venue = Venue.create("플로우홀", new Address("서울", "로3", "00000"), 1, 1);
            em.persist(venue);

            Seat seat = Seat.of(venue, 1, 1, SeatGrade.VIP);
            em.persist(seat);

            Event event = Event.create("플로우공연", "설명", LocalDate.now(), LocalDate.now().plusDays(1),
                    120, "출연", AgeLimit.ALL, Category.CONCERT, "url", seller);
            event.approve();
            em.persist(event);

            EventSchedule schedule = EventSchedule.create(venue, LocalDateTime.now().plusDays(1), 1);
            event.addSchedule(schedule);
            em.persist(schedule);

            EventSeat eventSeat = EventSeat.create(schedule, seat, 10000);
            em.persist(eventSeat);

            em.flush();
            memberId = member.getId();
            scheduleId = schedule.getId();
            eventSeatId = eventSeat.getId();
            return null;
        });


        redis.opsForZSet().add("queue:active:" + scheduleId, memberId.toString(),
                System.currentTimeMillis() + 600_000);
    }

    @AfterEach
    void cleanupRedis() {
        redis.delete("queue:active:" + scheduleId);
        redis.delete("seat:hold:" + scheduleId + ":" + eventSeatId);
    }

    @Test
    @DisplayName("예약(PENDING) → 결제(CONFIRMED) → 취소(CANCEL + 결제취소)")
    void 예약_결제_취소() {

        ReservationCreateDto createDto = new ReservationCreateDto();
        ReflectionTestUtils.setField(createDto, "scheduleId", scheduleId);
        ReflectionTestUtils.setField(createDto, "eventSeatIds", List.of(eventSeatId));
        ReflectionTestUtils.setField(createDto, "idempotencyKey", "idem-flow-1");

        ReservationResponseDto created = reservationService.create(memberId, createDto);
        Long reservationId = created.getId();

        Double activeScore = redis.opsForZSet()
                .score("queue:active:" + scheduleId, memberId.toString());

        assertThat(activeScore).isNull();

        System.out.printf("%n========== [예약·결제·취소 흐름] ==========%n" +
                        "  1) 예약생성 → 예약 %s (기대 PENDING) / 좌석 %d건 (기대 1)%n",
                created.getStatus(), created.getSeats().size());
        assertThat(created.getStatus()).isEqualTo(ReservationStatus.PENDING);
        assertThat(created.getSeats()).hasSize(1);   // ReservationSeat 생성 (DTO seats라 LazyInit 안전)


        PaymentCreateDto payDto = new PaymentCreateDto();
        ReflectionTestUtils.setField(payDto, "reservationId", reservationId);
        paymentService.pay(memberId, payDto);

        Reservation afterPay = reservationRepository.findById(reservationId).orElseThrow();
        var seatAfterPay = eventSeatRepository.findById(eventSeatId).orElseThrow().getStatus();
        boolean holdReleased = !redis.hasKey("seat:hold:" + scheduleId + ":" + eventSeatId);
        var paidAmount = paymentRepository.findByReservationId(reservationId).orElseThrow().getAmount();
        System.out.printf("  2) 결제완료 → 예약 %s (기대 CONFIRMED) / 좌석 %s (기대 RESERVED) / 선점해제 %s (기대 true) / 금액 %s (기대 10000)%n",
                afterPay.getStatus(), seatAfterPay, holdReleased, paidAmount);
        System.out.printf("[결제 COMMIT] 예약=%s | 좌석=%s | 임시 선점 해제=%s%n",
                afterPay.getStatus(), seatAfterPay, holdReleased);
        assertThat(afterPay.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(paymentRepository.existsByReservationId(reservationId)).isTrue();
        assertThat(seatAfterPay).isEqualTo(EventSeatStatus.RESERVED);
        assertThat(holdReleased).isTrue();
        assertThat(paidAmount).isEqualTo(10000);


        reservationService.cancel(reservationId, memberId, CancelReason.CHANGE_OF_MIND, null);

        Reservation afterCancel = reservationRepository.findById(reservationId).orElseThrow();
        var payStatusAfterCancel = paymentRepository.findByReservationId(reservationId).orElseThrow().getStatus();
        var seatAfterCancel = eventSeatRepository.findById(eventSeatId).orElseThrow().getStatus();
        System.out.printf("  3) 취소     → 예약 %s (기대 CANCEL) / 결제 %s (기대 CANCELED) / 좌석 %s (기대 AVAILABLE)%n" +
                        "=========================================%n",
                afterCancel.getStatus(), payStatusAfterCancel, seatAfterCancel);
        assertThat(afterCancel.getStatus()).isEqualTo(ReservationStatus.CANCEL);
        assertThat(payStatusAfterCancel).isEqualTo(PaymentStatus.CANCELED);
        assertThat(seatAfterCancel).isEqualTo(EventSeatStatus.AVAILABLE);
    }

    @Test
    @DisplayName("같은 idempotencyKey로 두 번 예약 → 1건만 생성 (멱등성)")
    void 예약_멱등성() {
        // given
        ReservationCreateDto dto = new ReservationCreateDto();
        ReflectionTestUtils.setField(dto, "scheduleId", scheduleId);
        ReflectionTestUtils.setField(dto, "eventSeatIds", List.of(eventSeatId));
        ReflectionTestUtils.setField(dto, "idempotencyKey", "idem-dup");

        // when : 같은 idempotencyKey로 두 번 생성
        reservationService.create(memberId, dto);
        reservationService.create(memberId, dto);

        // then : 멱등 분기로 1건만 생성
        long count = reservationRepository.count();
        System.out.printf("%n========== [예약 멱등성] ==========%n" +
                        "  같은 idempotencyKey로 2회 요청%n" +
                        "  기대값 : 예약 1건 / 실제값 : 예약 %d건%n" +
                        "==================================%n", count);
        assertThat(count).isEqualTo(1);   // 1건만 생성됨
    }

    @Test
    @DisplayName("결제 트랜잭션이 롤백되면 좌석 선점을 유지한다")
    void 결제_롤백_시_좌석_선점을_유지한다() {
        ReservationCreateDto createDto = new ReservationCreateDto();
        ReflectionTestUtils.setField(createDto, "scheduleId", scheduleId);
        ReflectionTestUtils.setField(createDto, "eventSeatIds", List.of(eventSeatId));
        ReflectionTestUtils.setField(createDto, "idempotencyKey", "idem-payment-rollback");

        Long reservationId = reservationService.create(memberId, createDto).getId();

        PaymentCreateDto payDto = new PaymentCreateDto();
        ReflectionTestUtils.setField(payDto, "reservationId", reservationId);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            paymentService.pay(memberId, payDto);
            throw new RuntimeException("결제 트랜잭션 롤백 테스트");
        })).isInstanceOf(RuntimeException.class);

        Reservation reservation = reservationRepository.findById(reservationId).orElseThrow();
        EventSeatStatus seatStatus = eventSeatRepository.findById(eventSeatId).orElseThrow().getStatus();
        String holdOwner = redis.opsForValue().get(
                "seat:hold:" + scheduleId + ":" + eventSeatId
        );
        boolean paymentExists = paymentRepository.existsByReservationId(reservationId);
        boolean holdMaintained = memberId.toString().equals(holdOwner);

        System.out.printf("[결제 ROLLBACK] 결제 존재=%s | 예약=%s | 좌석=%s | 선점 유지=%s%n",
                paymentExists, reservation.getStatus(), seatStatus, holdMaintained);

        assertThat(paymentExists).isFalse();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PENDING);
        assertThat(seatStatus).isEqualTo(EventSeatStatus.AVAILABLE);
        assertThat(holdMaintained).isTrue();
    }
}
