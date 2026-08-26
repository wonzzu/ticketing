package com.ticketing.outbox.messaging;

import com.ticketing.config.RabbitMqConfig;
import com.ticketing.outbox.dto.PaymentCanceledOutboxPayload;
import com.ticketing.outbox.exception.DuplicateMessageException;
import com.ticketing.outbox.service.PaymentCanceledMessageHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCanceledMessageConsumer {

    private final PaymentCanceledMessageHandler messageHandler;

    @RabbitListener(queues = RabbitMqConfig.PAYMENT_CANCELED_QUEUE)
    public void consume(PaymentCanceledOutboxPayload payload,
                        @Header("messageId") String messageId) {
        try {
            messageHandler.handle(messageId, payload);
            log.info("결제 취소 메시지 처리 완료: messageId={}, eventId={}",
                    messageId, payload.performanceEventId());
        } catch (DuplicateMessageException e) {
            log.info("중복 결제 취소 메시지 처리 생략: messageId={}", messageId);
        }
    }
}
