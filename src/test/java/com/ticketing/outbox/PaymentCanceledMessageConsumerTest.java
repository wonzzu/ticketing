package com.ticketing.outbox;

import com.ticketing.outbox.dto.PaymentCanceledOutboxPayload;
import com.ticketing.outbox.messaging.PaymentCanceledMessageConsumer;
import com.ticketing.outbox.service.PaymentCanceledMessageHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("결제 취소 메시지 Consumer")
class PaymentCanceledMessageConsumerTest {

    private final PaymentCanceledMessageHandler messageHandler = mock(PaymentCanceledMessageHandler.class);
    private final PaymentCanceledMessageConsumer consumer = new PaymentCanceledMessageConsumer(messageHandler);

    @Test
    @DisplayName("자동 변환된 결제 취소 Payload를 Handler에 전달한다")
    void delegatePayloadToHandler() {
        PaymentCanceledOutboxPayload payload = new PaymentCanceledOutboxPayload(
                1L, 2L, LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 19));

        consumer.consume(payload, "message-1");

        verify(messageHandler).handle(payload);
    }
}
