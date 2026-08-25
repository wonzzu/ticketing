package com.ticketing.outbox;

import com.ticketing.outbox.domain.OutboxEvent;
import com.ticketing.outbox.messaging.RabbitOutboxMessagePublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

@DisplayName("RabbitMQ Outbox 메시지 발행")
class RabbitOutboxMessagePublisherTest {

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final RabbitOutboxMessagePublisher messagePublisher =
            new RabbitOutboxMessagePublisher(rabbitTemplate, 5_000);

    @Test
    @DisplayName("Payload와 메시지 식별자를 발행하고 브로커 ACK를 확인한다")
    void publishAfterBrokerAck() {
        OutboxEvent outboxEvent = OutboxEvent.paymentCanceled(1L, "{\"paymentId\":1}");
        doAnswer(invocation -> {
            Message message = invocation.getArgument(2);
            CorrelationData correlationData = invocation.getArgument(3);

            assertThat(new String(message.getBody(), StandardCharsets.UTF_8))
                    .isEqualTo(outboxEvent.getPayload());
            assertThat((String) message.getMessageProperties().getHeader("messageId"))
                    .isEqualTo(outboxEvent.getMessageId());
            assertThat((String) message.getMessageProperties().getHeader("eventType"))
                    .isEqualTo(outboxEvent.getEventType().name());

            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(eq("ticketon.events"), eq("payment.canceled"),
                any(Message.class), any(CorrelationData.class));

        messagePublisher.publish(outboxEvent);
    }

    @Test
    @DisplayName("브로커가 NACK을 반환하면 발행 실패로 처리한다")
    void failWhenBrokerNack() {
        OutboxEvent outboxEvent = OutboxEvent.paymentCanceled(1L, "{\"paymentId\":1}");
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.getFuture().complete(new CorrelationData.Confirm(false, "exchange unavailable"));
            return null;
        }).when(rabbitTemplate).send(eq("ticketon.events"), eq("payment.canceled"),
                any(Message.class), any(CorrelationData.class));

        assertThatThrownBy(() -> messagePublisher.publish(outboxEvent))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RabbitMQ가 메시지를 확인하지 않았습니다.");
    }
}
