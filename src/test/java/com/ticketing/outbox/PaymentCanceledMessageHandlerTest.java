package com.ticketing.outbox;

import com.ticketing.outbox.dto.PaymentCanceledOutboxPayload;
import com.ticketing.outbox.service.PaymentCanceledMessageHandler;
import com.ticketing.settlement.service.SettlementDirtyService;
import com.ticketing.statistics.service.StatsDirtyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("결제 취소 메시지 Handler")
class PaymentCanceledMessageHandlerTest {

    private final SettlementDirtyService settlementDirtyService = mock(SettlementDirtyService.class);
    private final StatsDirtyService statsDirtyService = mock(StatsDirtyService.class);
    private final PaymentCanceledMessageHandler messageHandler =
            new PaymentCanceledMessageHandler(settlementDirtyService, statsDirtyService);

    @Test
    @DisplayName("결제 취소 일자를 정산과 통계 재집계 대상으로 등록한다")
    void markSettlementAndStatsDirty() {
        LocalDate settlementDate = LocalDate.of(2026, 8, 20);
        LocalDate paidDate = LocalDate.of(2026, 8, 19);
        PaymentCanceledOutboxPayload payload =
                new PaymentCanceledOutboxPayload(1L, 2L, settlementDate, paidDate);

        messageHandler.handle(payload);

        verify(settlementDirtyService).markDirtyIfSettled(1L, 2L, settlementDate);
        verify(statsDirtyService).markDirtyIfAggregated(paidDate);
    }
}
