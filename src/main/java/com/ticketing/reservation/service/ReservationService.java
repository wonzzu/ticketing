package com.ticketing.reservation.service;

import com.ticketing.event.domain.EventSchedule;
import com.ticketing.event.domain.EventSeat;
import com.ticketing.event.domain.EventSeatStatus;
import com.ticketing.event.repository.EventScheduleRepository;
import com.ticketing.event.repository.EventSeatRepository;
import com.ticketing.event.service.SeatHoldService;
import com.ticketing.global.exception.BaseException;
import com.ticketing.member.domain.Member;
import com.ticketing.member.repository.MemberRepository;
import com.ticketing.payment.service.PaymentService;
import com.ticketing.queue.service.QueueService;
import com.ticketing.reservation.domain.CancelReason;
import com.ticketing.reservation.domain.Reservation;
import com.ticketing.reservation.domain.ReservationHistory;
import com.ticketing.reservation.domain.ReservationSeat;
import com.ticketing.reservation.dto.request.ReservationCreateDto;
import com.ticketing.reservation.dto.request.ReservationSearchCond;
import com.ticketing.reservation.dto.response.ReservationResponseDto;
import com.ticketing.reservation.repository.ReservationHistoryRepository;
import com.ticketing.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.ticketing.global.baseresponse.BaseResponseStatus.*;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationService {

    private static final int MAX_SEATS = 3;

    private final ReservationRepository reservationRepository;
    private final EventScheduleRepository eventScheduleRepository;
    private final EventSeatRepository eventSeatRepository;
    private final MemberRepository memberRepository;
    private final SeatHoldService seatHoldService;
    private final QueueService queueService;
    private final ReservationHistoryRepository reservationHistoryRepository;
    private final PaymentService paymentService;


    @Transactional
    public ReservationResponseDto create(Long memberId, ReservationCreateDto dto) {
        Member member = memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new BaseException(MEMBER_NOT_FOUND));

        Optional<Reservation> exist = reservationRepository
                .findByMemberIdAndIdempotencyKey(memberId, dto.getIdempotencyKey());

        if (exist.isPresent()) {
            log.debug("멱등 재요청 - 기존 예매 반환: memberId={}, idempotencyKey={}",
                    memberId, dto.getIdempotencyKey());
            return ReservationResponseDto.from(exist.get());
        }

        if (!queueService.isAdmitted(dto.getScheduleId(), memberId)) {
            throw new BaseException(QUEUE_NOT_ADMITTED);
        }

        List<Long> seatIds = dto.getEventSeatIds().stream().sorted().toList();

        if (seatIds.isEmpty()) {
            throw new BaseException(EMPTY_SEAT_SELECTION);
        }
        if (seatIds.size() > MAX_SEATS) {
            throw new BaseException(EXCEED_SEAT_LIMIT);
        }

        EventSchedule schedule = eventScheduleRepository.findById(dto.getScheduleId())
                .orElseThrow(() -> new BaseException(PERFORMANCE_NOT_FOUND));

        if (schedule.getShowDateTime().isBefore(LocalDateTime.now())) {
            throw new BaseException(SCHEDULE_ALREADY_STARTED);
        }

        List<EventSeat> seats = eventSeatRepository.findAllById(seatIds);

        if (seats.size() != seatIds.size()) {
            throw new BaseException(SEAT_NOT_FOUND);
        }

        boolean mismatched = seats.stream()
                .anyMatch(s -> !s.getEventSchedule().getId().equals(schedule.getId()));
        if (mismatched) {
            throw new BaseException(SEAT_SCHEDULE_MISMATCH);
        }

        boolean anySold = seats.stream().anyMatch(s -> s.getStatus() == EventSeatStatus.RESERVED);
        if (anySold) {
            throw new BaseException(SEAT_ALREADY_RESERVED);
        }

        if (!seatHoldService.holdAll(dto.getScheduleId(), seatIds, memberId)) {
            throw new BaseException(SEAT_NOT_AVAILABLE);
        }

        registerTxCallbacks(dto.getScheduleId(), seatIds, memberId);

        int totalPrice = seats.stream().mapToInt(EventSeat::getPrice).sum();

        Reservation reservation = Reservation.create(member, schedule, dto.getIdempotencyKey(), totalPrice);

        for (EventSeat seat : seats) {
            reservation.addReservationSeat(ReservationSeat.create(seat, seat.getPrice()));
        }
        reservationRepository.save(reservation);

        reservationHistoryRepository.save(ReservationHistory.of(reservation));

        log.info("예매 생성: memberId={},reservationId={},좌석 {}개,금액 ={}", memberId, reservation.getId(), seats.size(), totalPrice);

        return ReservationResponseDto.from(reservation);
    }

    public Page<ReservationResponseDto> findMine(Long memberId, ReservationSearchCond cond, Pageable pageable) {

        return reservationRepository.search(memberId, cond, pageable)
                .map(ReservationResponseDto::from);
    }

    public ReservationResponseDto findOne(Long reservationId, Long memberId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BaseException(RESERVATION_NOT_FOUND));

        if (!reservation.isOwnedBy(memberId)) {
            throw new BaseException(RESERVATION_NOT_OWNED);
        }

        return ReservationResponseDto.from(reservation);
    }

    @Transactional
    public void cancel(Long reservationId, Long memberId, CancelReason cancelReason, String detail) {

        if (cancelReason == CancelReason.OTHER && (detail == null || detail.isBlank())) {
            throw new BaseException(CANCEL_DETAIL_REQUIRED);
        }

        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BaseException(RESERVATION_NOT_FOUND));

        if (!reservation.isOwnedBy(memberId)) {
            throw new BaseException(RESERVATION_NOT_OWNED);
        }

        reservation.cancel();
        reservation.getReservationSeats().forEach(rs -> rs.getEventSeat().cancel());

        Long scheduleId = reservation.getEventSchedule().getId();
        List<Long> seatIds = reservation.getReservationSeats().stream()
                .map(rs -> rs.getEventSeat().getId()).toList();

        seatHoldService.releaseAll(scheduleId, seatIds, memberId);

        paymentService.cancelByReservation(reservationId, cancelReason.getDescription());

        reservationHistoryRepository.save(ReservationHistory.ofCancel(reservation, cancelReason, detail));

        log.info("예매 취소: reservationId={},memberId={},reason={}", reservationId, memberId, cancelReason);
    }

    private void registerTxCallbacks(Long scheduleId, List<Long> seatIds, Long memberId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    queueService.leave(scheduleId, memberId);
                    log.info("예매 커밋 후 대기열 active 제거: scheduleId={}, memberId={}", scheduleId, memberId);
                } catch (RuntimeException e) {
                    log.error("예매 커밋 후 대기열 active 제거 실패: scheduleId={}, memberId={}",
                            scheduleId, memberId, e);
                }
            }

            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_ROLLED_BACK) {
                    return;
                }
                try {
                    seatHoldService.releaseAll(scheduleId, seatIds, memberId);
                    log.warn("예매 트랜잭션 롤백으로 좌석 선점 해제: scheduleId={}, memberId={}, seatIds={}",
                            scheduleId, memberId, seatIds);
                } catch (RuntimeException e) {
                    log.error("예매 롤백 후 좌석 선점 해제 실패: scheduleId={}, memberId={}, seatIds={}",
                            scheduleId, memberId, seatIds, e);
                }
            }
        });
    }
}
