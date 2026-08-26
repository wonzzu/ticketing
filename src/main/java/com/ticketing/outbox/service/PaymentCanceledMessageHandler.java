package com.ticketing.outbox.service;

import com.ticketing.outbox.dto.PaymentCanceledOutboxPayload;
import com.ticketing.outbox.domain.OutboxConsumerType;
import com.ticketing.outbox.domain.ProcessedMessage;
import com.ticketing.outbox.exception.DuplicateMessageException;
import com.ticketing.outbox.repository.ProcessedMessageRepository;
import com.ticketing.settlement.service.SettlementDirtyService;
import com.ticketing.statistics.service.StatsDirtyService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentCanceledMessageHandler {

    private final ProcessedMessageRepository processedMessageRepository;
    private final SettlementDirtyService settlementDirtyService;
    private final StatsDirtyService statsDirtyService;

    @Transactional
    public void handle(String messageId, PaymentCanceledOutboxPayload payload) {
        registerProcessedMessage(messageId);

        settlementDirtyService.markDirtyIfSettled(
                payload.sellerId(), payload.performanceEventId(), payload.settlementDate());
        statsDirtyService.markDirtyIfAggregated(payload.paidDate());
    }

    private void registerProcessedMessage(String messageId) {
        try {
            processedMessageRepository.saveAndFlush(ProcessedMessage.of(
                    OutboxConsumerType.REAGGREGATION, messageId));
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateMessageException(
                    OutboxConsumerType.REAGGREGATION.name(), messageId, e);
        }
    }
}
