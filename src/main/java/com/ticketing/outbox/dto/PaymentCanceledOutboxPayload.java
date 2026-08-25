package com.ticketing.outbox.dto;

import java.time.LocalDate;

public record PaymentCanceledOutboxPayload(
        Long sellerId,
        Long performanceEventId,
        LocalDate settlementDate,
        LocalDate paidDate
) {
}
