package com.ticketing.outbox;

import com.ticketing.outbox.domain.OutboxEvent;
import com.ticketing.outbox.domain.OutboxEventStatus;
import com.ticketing.outbox.messaging.OutboxMessagePublisher;
import com.ticketing.outbox.repository.OutboxEventRepository;
import com.ticketing.outbox.service.OutboxRelayService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Outbox Relay")
class OutboxRelayServiceTest {

    private final OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
    private final OutboxMessagePublisher messagePublisher = mock(OutboxMessagePublisher.class);
    private final OutboxRelayService outboxRelayService =
            new OutboxRelayService(outboxEventRepository, messagePublisher, 20);

    @Test
    @DisplayName("RabbitMQ 발행에 성공한 메시지만 PUBLISHED로 변경한다")
    void markPublishedOnlyAfterPublishSuccess() {
        OutboxEvent outboxEvent = OutboxEvent.paymentCanceled(1L, "{\"paymentId\":1}");
        when(outboxEventRepository.findByStatusOrderByIdAsc(
                OutboxEventStatus.PENDING, PageRequest.of(0, 20)))
                .thenReturn(List.of(outboxEvent));

        int publishedCount = outboxRelayService.publishPending();

        assertThat(publishedCount).isEqualTo(1);
        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        verify(messagePublisher).publish(outboxEvent);
        verify(outboxEventRepository).save(outboxEvent);
    }

    @Test
    @DisplayName("RabbitMQ 발행에 실패한 메시지는 PENDING으로 유지한다")
    void keepPendingWhenPublishFails() {
        OutboxEvent outboxEvent = OutboxEvent.paymentCanceled(1L, "{\"paymentId\":1}");
        when(outboxEventRepository.findByStatusOrderByIdAsc(
                OutboxEventStatus.PENDING, PageRequest.of(0, 20)))
                .thenReturn(List.of(outboxEvent));
        doThrow(new IllegalStateException("RabbitMQ 연결 실패")).when(messagePublisher).publish(outboxEvent);

        assertThatThrownBy(outboxRelayService::publishPending)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RabbitMQ 연결 실패");
        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
    }
}
