package com.ticketing.outbox;

import com.ticketing.config.RabbitMqConfig;
import com.ticketing.outbox.domain.OutboxEvent;
import com.ticketing.outbox.messaging.RabbitOutboxMessagePublisher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("local")
@DisplayName("RabbitMQ Outbox 실제 발행")
class RabbitOutboxMessagePublisherIntegrationTest {

    private static CachingConnectionFactory connectionFactory;
    private static RabbitTemplate rabbitTemplate;
    private static RabbitAdmin rabbitAdmin;
    private static RabbitOutboxMessagePublisher messagePublisher;

    @BeforeAll
    static void setUpRabbitMq() {
        connectionFactory = new CachingConnectionFactory("localhost", 5672);
        connectionFactory.setUsername("ticketon");
        connectionFactory.setPassword("ticketon-local-password");
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connectionFactory.setPublisherReturns(true);

        rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMandatory(true);
        rabbitAdmin = new RabbitAdmin(connectionFactory);

        RabbitMqConfig config = new RabbitMqConfig();
        TopicExchange exchange = config.ticketonEventExchange();
        Queue queue = config.paymentCanceledQueue();
        Binding binding = config.paymentCanceledBinding(exchange, queue);
        rabbitAdmin.declareExchange(exchange);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(binding);

        messagePublisher = new RabbitOutboxMessagePublisher(rabbitTemplate, 5_000);
    }

    @BeforeEach
    void purgeQueue() {
        rabbitAdmin.purgeQueue(RabbitMqConfig.PAYMENT_CANCELED_QUEUE, false);
    }

    @AfterAll
    static void closeConnection() {
        connectionFactory.destroy();
    }

    @Test
    @DisplayName("발행한 결제 취소 메시지가 Queue에 보존된다")
    void publishToRealQueue() {
        OutboxEvent outboxEvent = OutboxEvent.paymentCanceled(1L, "{\"paymentId\":1}");

        messagePublisher.publish(outboxEvent);

        Message received = rabbitTemplate.receive(RabbitMqConfig.PAYMENT_CANCELED_QUEUE, 3_000);
        assertThat(received).isNotNull();
        assertThat(new String(received.getBody(), StandardCharsets.UTF_8)).isEqualTo(outboxEvent.getPayload());
        assertThat((String) received.getMessageProperties().getHeader("messageId"))
                .isEqualTo(outboxEvent.getMessageId());
        assertThat((String) received.getMessageProperties().getHeader("eventType"))
                .isEqualTo(outboxEvent.getEventType().name());
    }
}
