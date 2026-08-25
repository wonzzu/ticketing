package com.ticketing.outbox.repository;

import com.ticketing.outbox.domain.OutboxEvent;
import com.ticketing.outbox.domain.OutboxEventStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    Optional<OutboxEvent> findByMessageId(String messageId);

    long countByStatus(OutboxEventStatus status);

    List<OutboxEvent> findByStatusOrderByIdAsc(OutboxEventStatus status, Pageable pageable);
}
