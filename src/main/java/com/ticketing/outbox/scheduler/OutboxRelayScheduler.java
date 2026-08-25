package com.ticketing.outbox.scheduler;

import com.ticketing.outbox.service.OutboxRelayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayScheduler {

    private final OutboxRelayService outboxRelayService;

    @Scheduled(fixedDelayString = "${outbox.relay.fixed-delay-ms:1000}")
    public void relay() {
        try {
            int publishedCount = outboxRelayService.publishPending();
            if (publishedCount > 0) {
                log.info("Outbox 메시지 발행 완료: count={}", publishedCount);
            }
        } catch (RuntimeException e) {
            log.error("Outbox 메시지 발행 실패 - 다음 주기에 재시도합니다.", e);
        }
    }
}
