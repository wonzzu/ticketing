package com.ticketing.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.outbox.messaging.RabbitOutboxMessagePublisher;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

@Configuration
public class RabbitMqConfig {

    public static final String PAYMENT_CANCELED_QUEUE = "ticketon.payment-canceled.reaggregation";

    @Bean
    public TopicExchange ticketonEventExchange() {
        return new TopicExchange(RabbitOutboxMessagePublisher.EXCHANGE, true, false);
    }

    @Bean
    public Queue paymentCanceledQueue() {
        return QueueBuilder.durable(PAYMENT_CANCELED_QUEUE).build();
    }

    @Bean
    public Binding paymentCanceledBinding(TopicExchange ticketonEventExchange, Queue paymentCanceledQueue) {
        return BindingBuilder.bind(paymentCanceledQueue)
                .to(ticketonEventExchange)
                .with(RabbitOutboxMessagePublisher.PAYMENT_CANCELED_ROUTING_KEY);
    }

    @Bean
    public Jackson2JsonMessageConverter rabbitMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
