package com.ticketing.statistics.service;

import com.ticketing.statistics.domain.StatsDirtyDate;
import com.ticketing.statistics.repository.DailySalesStatsRepository;
import com.ticketing.statistics.repository.StatsDirtyDateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Slf4j
@Service
@RequiredArgsConstructor
public class StatsDirtyService {

    private final DailySalesStatsRepository dailySalesStatsRepository;
    private final StatsDirtyDateRepository dirtyDateRepository;

    @Transactional
    public void markDirtyIfAggregated(LocalDate paidDate) {
        if (!dailySalesStatsRepository.existsByStatDate(paidDate)) return;
        if (dirtyDateRepository.existsByStatDate(paidDate)) return;

        dirtyDateRepository.save(StatsDirtyDate.of(paidDate));
        log.info("통계 재집계 대기 등록: date={}", paidDate);
    }
}
