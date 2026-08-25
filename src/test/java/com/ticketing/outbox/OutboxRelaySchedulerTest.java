package com.ticketing.outbox;

import com.ticketing.outbox.scheduler.OutboxRelayScheduler;
import com.ticketing.outbox.service.OutboxRelayService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("Outbox Relay Scheduler")
class OutboxRelaySchedulerTest {

    private final OutboxRelayService outboxRelayService = mock(OutboxRelayService.class);
    private final OutboxRelayScheduler scheduler = new OutboxRelayScheduler(outboxRelayService);

    @Test
    @DisplayName("주기마다 PENDING 메시지 발행을 요청한다")
    void relayPendingEvents() {
        scheduler.relay();

        verify(outboxRelayService).publishPending();
    }

    @Test
    @DisplayName("발행 실패가 발생해도 다음 주기 실행을 위해 예외를 전파하지 않는다")
    void keepSchedulerAliveWhenPublishFails() {
        doThrow(new IllegalStateException("RabbitMQ 연결 실패"))
                .when(outboxRelayService).publishPending();

        assertThatCode(scheduler::relay).doesNotThrowAnyException();
    }
}
