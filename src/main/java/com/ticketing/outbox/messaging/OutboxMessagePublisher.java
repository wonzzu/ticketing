package com.ticketing.outbox.messaging;

import com.ticketing.outbox.domain.OutboxEvent;

public interface OutboxMessagePublisher {

    void publish(OutboxEvent outboxEvent);
}
