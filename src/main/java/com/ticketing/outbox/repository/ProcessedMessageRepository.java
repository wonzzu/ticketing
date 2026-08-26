package com.ticketing.outbox.repository;

import com.ticketing.outbox.domain.OutboxConsumerType;
import com.ticketing.outbox.domain.ProcessedMessage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessage, Long> {

    long countByConsumerTypeAndMessageId(OutboxConsumerType consumerType, String messageId);
}
