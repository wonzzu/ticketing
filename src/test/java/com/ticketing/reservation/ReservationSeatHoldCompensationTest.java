package com.ticketing.reservation;

import com.ticketing.event.domain.*;
import com.ticketing.event.service.SeatHoldService;
import com.ticketing.global.entity.Address;
import com.ticketing.member.domain.Gender;
import com.ticketing.member.domain.NormalMember;
import com.ticketing.member.domain.Seller;
import com.ticketing.reservation.dto.request.ReservationCreateDto;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Sql(scripts = "/truncate.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
@DisplayName("예매 - 좌석 선점 보상")
class ReservationSeatHoldCompensationTest {

    @Autowired ReservationService reservationService;
    @Autowired ReservationRepository reservationRepository;
    @Autowired SeatHoldService seatHoldService;
    @Autowired StringRedisTemplate redis;
    @Autowired TransactionTemplate tx;
    @PersistenceContext EntityManager em;

    private Long memberAId;
    private Long memberBId;
    private Long scheduleId;
    private Long eventSeatId;

    @BeforeEach
    void setup() {
        tx.executeWithoutResult(status -> {
            Seller seller = Seller.create("compensation-seller@test.com", "pw", "판매자", "010-0000-0000",
                    new Address("서울", "테스트로 1", "00000"), "테스트컴퍼니", "대표자", "000-00-00001");
            em.persist(seller);

            NormalMember memberA = NormalMember.create("compensation-a@test.com", "pw", "회원A", "회원A닉네임",
                    LocalDate.of(1990, 1, 1), Gender.MALE, "010-0000-0001",
                    new Address("서울", "테스트로 2", "00000"));
            em.persist(memberA);

            NormalMember memberB = NormalMember.create("compensation-b@test.com", "pw", "회원B", "회원B닉네임",
                    LocalDate.of(1991, 1, 1), Gender.FEMALE, "010-0000-0002",
                    new Address("서울", "테스트로 3", "00000"));
            em.persist(memberB);

            Venue venue = Venue.create("보상 테스트 공연장",
                    new Address("서울", "테스트로 4", "00000"), 1, 1);
            em.persist(venue);

            Seat seat = Seat.of(venue, 1, 1, SeatGrade.VIP);
            em.persist(seat);

            Event event = Event.create("보상 테스트 공연", "설명", LocalDate.now(),
                    LocalDate.now().plusDays(1), 120, "출연자", AgeLimit.ALL,
                    Category.CONCERT, "poster-url", seller);
            event.approve();
            em.persist(event);

            EventSchedule schedule = EventSchedule.create(venue, LocalDateTime.now().plusDays(1), 1);
            event.addSchedule(schedule);
            em.persist(schedule);

            EventSeat eventSeat = EventSeat.create(schedule, seat, 10_000);
            em.persist(eventSeat);
            em.flush();

            memberAId = memberA.getId();
            memberBId = memberB.getId();
            scheduleId = schedule.getId();
            eventSeatId = eventSeat.getId();
        });

        redis.opsForZSet().add("queue:active:" + scheduleId, memberAId.toString(),
                System.currentTimeMillis() + 600_000);
    }

    @AfterEach
    void cleanupRedis() {
        redis.delete("queue:active:" + scheduleId);
        redis.delete("seat:hold:" + scheduleId + ":" + eventSeatId);
    }

    @Test
    @DisplayName("좌석 선점 후 예매 저장이 실패하면 선점을 해제한다")
    void 좌석_선점후_예매저장_실패시_선점을_보상해제한다() {
        // given: DB 컬럼 길이를 초과시켜 좌석 선점 이후 INSERT를 실패시킨다.
        ReservationCreateDto request = new ReservationCreateDto();
        ReflectionTestUtils.setField(request, "scheduleId", scheduleId);
        ReflectionTestUtils.setField(request, "eventSeatIds", List.of(eventSeatId));
        ReflectionTestUtils.setField(request, "idempotencyKey", "x".repeat(300));

        // when
        assertThatThrownBy(() -> reservationService.create(memberAId, request))
                .isInstanceOf(DataIntegrityViolationException.class);

        Double activeScore = redis.opsForZSet()
                .score("queue:active:" + scheduleId, memberAId.toString());

        assertThat(activeScore).isNotNull();

        // then: DB는 rollback되고 Redis 좌석 선점도 보상 해제돼야 한다.
        long reservationCount = reservationRepository.count();
        boolean holdExists = redis.hasKey("seat:hold:" + scheduleId + ":" + eventSeatId);
        boolean heldByMemberB = seatHoldService.holdAll(scheduleId, List.of(eventSeatId), memberBId);

        System.out.printf(
                "[예매 ROLLBACK] 예매=%d건 | 잔여 선점=%s | 다른 회원 재선점=%s%n",
                reservationCount, holdExists, heldByMemberB
        );

        assertThat(reservationCount).isZero();
        assertThat(holdExists).isFalse();
        assertThat(heldByMemberB).isTrue();
    }
}
