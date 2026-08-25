package com.ticketing.outbox.messaging;

import com.ticketing.outbox.domain.OutboxEvent;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class RabbitOutboxMessagePublisher implements OutboxMessagePublisher {

    public static final String EXCHANGE = "ticketon.events";
    public static final String PAYMENT_CANCELED_ROUTING_KEY = "payment.canceled";

    private final RabbitTemplate rabbitTemplate;
    private final long confirmTimeoutMs;

    public RabbitOutboxMessagePublisher(RabbitTemplate rabbitTemplate,
                                        @Value("${outbox.relay.confirm-timeout-ms:5000}") long confirmTimeoutMs) {
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeoutMs = confirmTimeoutMs;
    }

    @Override
    public void publish(OutboxEvent outboxEvent) {
        Message message = MessageBuilder
                .withBody(outboxEvent.getPayload().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setHeader("messageId", outboxEvent.getMessageId())
                .setHeader("eventType", outboxEvent.getEventType().name())
                .build();
        CorrelationData correlationData = new CorrelationData(outboxEvent.getMessageId());

        rabbitTemplate.send(EXCHANGE, PAYMENT_CANCELED_ROUTING_KEY, message, correlationData);
        CorrelationData.Confirm confirm = awaitConfirm(correlationData);

        if (!confirm.isAck()) {
            throw new IllegalStateException("RabbitMQ가 메시지를 확인하지 않았습니다. reason=" + confirm.getReason());
        }
        if (correlationData.getReturned() != null) {
            throw new IllegalStateException("RabbitMQ 메시지가 Queue로 전달되지 않았습니다.");
        }
    }

    private CorrelationData.Confirm awaitConfirm(CorrelationData correlationData) {
        try {
            return correlationData.getFuture().get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("RabbitMQ 발행 확인 대기 중 스레드가 중단됐습니다.", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("RabbitMQ 발행 확인에 실패했습니다.", e);
        }
    }
}
