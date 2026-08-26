package com.ticketing.settlement.service;


import com.ticketing.settlement.domain.SettlementDirtyDate;
import com.ticketing.settlement.repository.SettlementDirtyDateRepository;
import com.ticketing.settlement.repository.SettlementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementDirtyService {

    private final SettlementRepository settlementRepository;
    private final SettlementDirtyDateRepository dirtyDateRepository;

    @Transactional
    public void markDirtyIfSettled(Long sellerId, Long eventId, LocalDate settlementDate) {

        if (!settlementRepository.existsByEventId(eventId)) return;

        if (dirtyDateRepository.existsBySellerIdAndEventIdAndSettlementDate(sellerId,eventId,settlementDate)) return;

        dirtyDateRepository.save(SettlementDirtyDate.of(sellerId, eventId, settlementDate));

        log.info("정산 재집계 대기 등록: sellerId={}, eventId={}, date={}", sellerId, eventId, settlementDate);
    }
}
