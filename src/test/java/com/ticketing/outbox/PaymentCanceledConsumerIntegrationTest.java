package com.ticketing.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.config.RabbitMqConfig;
import com.ticketing.outbox.domain.OutboxEvent;
import com.ticketing.outbox.dto.PaymentCanceledOutboxPayload;
import com.ticketing.outbox.messaging.RabbitOutboxMessagePublisher;
import com.ticketing.settlement.domain.Settlement;
import com.ticketing.settlement.repository.SettlementDirtyDateRepository;
import com.ticketing.settlement.repository.SettlementRepository;
import com.ticketing.statistics.domain.DailySalesStats;
import com.ticketing.statistics.repository.DailySalesStatsRepository;
import com.ticketing.statistics.repository.StatsDirtyDateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("local")
@SpringBootTest
@ActiveProfiles("test")
@Sql(scripts = "/truncate.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
@DisplayName("결제 취소 메시지 Consumer 통합 테스트")
class PaymentCanceledConsumerIntegrationTest {

    @Autowired RabbitOutboxMessagePublisher messagePublisher;
    @Autowired RabbitAdmin rabbitAdmin;
    @Autowired SettlementRepository settlementRepository;
    @Autowired DailySalesStatsRepository dailySalesStatsRepository;
    @Autowired SettlementDirtyDateRepository settlementDirtyDateRepository;
    @Autowired StatsDirtyDateRepository statsDirtyDateRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void purgeQueue() {
        rabbitAdmin.purgeQueue(RabbitMqConfig.PAYMENT_CANCELED_QUEUE, false);
    }

    @Test
    @DisplayName("RabbitMQ 메시지를 DTO로 자동 변환해 정산과 통계 Dirty를 등록한다")
    void consumeAndMarkDirty() throws InterruptedException, JsonProcessingException {
        Long sellerId = 1L;
        Long performanceEventId = 2L;
        LocalDate settlementDate = LocalDate.of(2026, 8, 20);
        LocalDate paidDate = LocalDate.of(2026, 8, 19);

        transactionTemplate.executeWithoutResult(status -> {
            settlementRepository.save(Settlement.of(
                    sellerId, performanceEventId, settlementDate, 100_000, 5_000, 95_000));
            dailySalesStatsRepository.save(DailySalesStats.of(paidDate, 1, 100_000));
        });

        PaymentCanceledOutboxPayload payload = new PaymentCanceledOutboxPayload(
                sellerId, performanceEventId, settlementDate, paidDate);
        OutboxEvent outboxEvent = OutboxEvent.paymentCanceled(10L, objectMapper.writeValueAsString(payload));
        messagePublisher.publish(outboxEvent);

        waitUntilDirtyCreated(sellerId, performanceEventId, settlementDate, paidDate);

        assertThat(settlementDirtyDateRepository.existsBySellerIdAndEventIdAndSettlementDate(
                sellerId, performanceEventId, settlementDate)).isTrue();
        assertThat(statsDirtyDateRepository.existsByStatDate(paidDate)).isTrue();
    }

    private void waitUntilDirtyCreated(Long sellerId, Long eventId,
                                       LocalDate settlementDate, LocalDate paidDate)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            boolean settlementDirty = settlementDirtyDateRepository
                    .existsBySellerIdAndEventIdAndSettlementDate(sellerId, eventId, settlementDate);
            boolean statsDirty = statsDirtyDateRepository.existsByStatDate(paidDate);
            if (settlementDirty && statsDirty) return;
            Thread.sleep(50);
        }
    }
}
