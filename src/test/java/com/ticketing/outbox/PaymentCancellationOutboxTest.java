package com.ticketing.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.event.domain.AgeLimit;
import com.ticketing.event.domain.Category;
import com.ticketing.event.domain.Event;
import com.ticketing.event.domain.EventSchedule;
import com.ticketing.global.entity.Address;
import com.ticketing.member.domain.Gender;
import com.ticketing.member.domain.NormalMember;
import com.ticketing.member.domain.Seller;
import com.ticketing.outbox.domain.OutboxEvent;
import com.ticketing.outbox.domain.OutboxEventStatus;
import com.ticketing.outbox.domain.OutboxEventType;
import com.ticketing.outbox.dto.PaymentCanceledOutboxPayload;
import com.ticketing.outbox.repository.OutboxEventRepository;
import com.ticketing.payment.domain.Payment;
import com.ticketing.payment.domain.PaymentStatus;
import com.ticketing.payment.repository.PaymentRepository;
import com.ticketing.reservation.domain.CancelReason;
import com.ticketing.reservation.domain.Reservation;
import com.ticketing.reservation.domain.ReservationStatus;
import com.ticketing.reservation.repository.ReservationRepository;
import com.ticketing.reservation.service.ReservationService;
import com.ticketing.venue.domain.Venue;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@SpringBootTest
@ActiveProfiles("test")
@Sql(scripts = "/truncate.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
@DisplayName("결제 취소 - Transactional Outbox")
class PaymentCancellationOutboxTest {

    @Autowired ReservationService reservationService;
    @Autowired ReservationRepository reservationRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired TransactionTemplate tx;
    @MockitoSpyBean ObjectMapper objectMapper;
    @PersistenceContext EntityManager em;

    private final LocalDate settlementDate = LocalDate.now().minusDays(1);

    private Long memberId;
    private Long reservationId;
    private Long paymentId;
    private Long sellerId;
    private Long performanceEventId;

    @BeforeEach
    void setup() {
        tx.executeWithoutResult(status -> {
            Seller seller = Seller.create("outbox-seller@test.com", "pw", "판매자", "010-0000-0000",
                    new Address("서울", "로1", "00000"), "컴퍼니", "대표", "000-00-00001");
            em.persist(seller);

            NormalMember member = NormalMember.create("outbox-member@test.com", "pw", "회원", "닉네임",
                    LocalDate.of(1990, 1, 1), Gender.MALE, "010-0000-0001", new Address("서울", "로2", "00000"));
            em.persist(member);

            Venue venue = Venue.create("아웃박스홀", new Address("서울", "로3", "00000"), 1, 1);
            em.persist(venue);

            Event event = Event.create("아웃박스공연", "설명", settlementDate.minusDays(3), settlementDate,
                    120, "출연", AgeLimit.ALL, Category.CONCERT, "url", seller);
            em.persist(event);

            EventSchedule schedule = EventSchedule.create(venue, settlementDate.atTime(19, 0), 1);
            event.addSchedule(schedule);
            em.persist(schedule);

            Reservation reservation = Reservation.create(member, schedule, "outbox-idempotency-key", 100_000);
            reservation.confirm();
            em.persist(reservation);

            Payment payment = Payment.paid(reservation, 100_000);
            em.persist(payment);
            em.flush();

            memberId = member.getId();
            reservationId = reservation.getId();
            paymentId = payment.getId();
            sellerId = seller.getId();
            performanceEventId = event.getId();
        });
    }

    @AfterEach
    void resetObjectMapperSpy() {
        reset(objectMapper);
    }

    @Test
    @DisplayName("결제 취소와 발행할 이벤트를 같은 트랜잭션에 저장한다")
    void 결제_취소와_아웃박스_이벤트를_함께_저장한다() throws Exception {
        reservationService.cancel(reservationId, memberId, CancelReason.CHANGE_OF_MIND, null);

        Payment payment = paymentRepository.findById(paymentId).orElseThrow();
        OutboxEvent outbox = outboxEventRepository.findAll().get(0);
        PaymentCanceledOutboxPayload payload = objectMapper.readValue(outbox.getPayload(), PaymentCanceledOutboxPayload.class);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
        assertThat(outbox.getMessageId()).isNotBlank();
        assertThat(outbox.getEventType()).isEqualTo(OutboxEventType.PAYMENT_CANCELED);
        assertThat(outbox.getAggregateType()).isEqualTo("PAYMENT");
        assertThat(outbox.getAggregateId()).isEqualTo(paymentId);
        assertThat(outbox.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(payload.sellerId()).isEqualTo(sellerId);
        assertThat(payload.performanceEventId()).isEqualTo(performanceEventId);
        assertThat(payload.settlementDate()).isEqualTo(settlementDate);
        assertThat(payload.paidDate()).isNotNull();
    }

    @Test
    @DisplayName("Outbox payload 생성에 실패하면 결제 취소도 롤백한다")
    void 아웃박스_payload_생성_실패시_결제_취소도_롤백한다() throws Exception {
        doThrow(new JsonProcessingException("직렬화 실패") {})
                .when(objectMapper).writeValueAsString(any(PaymentCanceledOutboxPayload.class));

        assertThatThrownBy(() -> reservationService.cancel(reservationId, memberId, CancelReason.CHANGE_OF_MIND, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("결제 취소 Outbox payload 직렬화에 실패했습니다.");

        Payment payment = paymentRepository.findById(paymentId).orElseThrow();
        Reservation reservation = reservationRepository.findById(reservationId).orElseThrow();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(outboxEventRepository.count()).isZero();
    }
}
