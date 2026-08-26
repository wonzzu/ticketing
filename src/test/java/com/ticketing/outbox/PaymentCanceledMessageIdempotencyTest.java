package com.ticketing.outbox;

import com.ticketing.outbox.domain.OutboxConsumerType;
import com.ticketing.outbox.dto.PaymentCanceledOutboxPayload;
import com.ticketing.outbox.repository.ProcessedMessageRepository;
import com.ticketing.outbox.service.PaymentCanceledMessageHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import com.ticketing.outbox.exception.DuplicateMessageException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("local")
@SpringBootTest
@ActiveProfiles("test")
@Sql(scripts = "/truncate.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
@DisplayName("결제 취소 메시지 멱등 처리")
class PaymentCanceledMessageIdempotencyTest {

    @Autowired PaymentCanceledMessageHandler messageHandler;
    @Autowired ProcessedMessageRepository processedMessageRepository;

    @Test
    @DisplayName("동일한 메시지를 두 번 전달해도 재집계 Consumer는 한 번만 처리한다")
    void ignoreDuplicatedMessage() {
        String messageId = "message-1";
        PaymentCanceledOutboxPayload payload = new PaymentCanceledOutboxPayload(
                1L, 2L, LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 19));

        messageHandler.handle(messageId, payload);
        assertThatThrownBy(() -> messageHandler.handle(messageId, payload))
                .isInstanceOf(DuplicateMessageException.class);

        assertThat(processedMessageRepository.countByConsumerTypeAndMessageId(
                OutboxConsumerType.REAGGREGATION, messageId)).isEqualTo(1);
    }
}
