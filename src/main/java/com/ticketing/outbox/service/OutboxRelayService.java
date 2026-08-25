package com.ticketing.outbox.service;

import com.ticketing.outbox.domain.OutboxEvent;
import com.ticketing.outbox.domain.OutboxEventStatus;
import com.ticketing.outbox.messaging.OutboxMessagePublisher;
import com.ticketing.outbox.repository.OutboxEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OutboxRelayService {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxMessagePublisher messagePublisher;
    private final int batchSize;

    public OutboxRelayService(OutboxEventRepository outboxEventRepository,
                              OutboxMessagePublisher messagePublisher,
                              @Value("${outbox.relay.batch-size:20}") int batchSize) {
        this.outboxEventRepository = outboxEventRepository;
        this.messagePublisher = messagePublisher;
        this.batchSize = batchSize;
    }

    public int publishPending() {
        List<OutboxEvent> pendingEvents = outboxEventRepository
                .findByStatusOrderByIdAsc(OutboxEventStatus.PENDING, PageRequest.of(0, batchSize));

        for (OutboxEvent pendingEvent : pendingEvents) {
            messagePublisher.publish(pendingEvent);
            pendingEvent.markPublished();
            outboxEventRepository.save(pendingEvent);
        }

        return pendingEvents.size();
    }
}
