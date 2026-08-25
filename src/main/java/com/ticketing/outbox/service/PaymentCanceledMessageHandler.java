package com.ticketing.outbox.service;

import com.ticketing.outbox.dto.PaymentCanceledOutboxPayload;
import com.ticketing.settlement.service.SettlementDirtyService;
import com.ticketing.statistics.service.StatsDirtyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentCanceledMessageHandler {

    private final SettlementDirtyService settlementDirtyService;
    private final StatsDirtyService statsDirtyService;

    public void handle(PaymentCanceledOutboxPayload payload) {
        settlementDirtyService.markDirtyIfSettled(
                payload.sellerId(), payload.performanceEventId(), payload.settlementDate());
        statsDirtyService.markDirtyIfAggregated(payload.paidDate());
    }
}
