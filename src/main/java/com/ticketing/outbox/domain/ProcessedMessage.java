package com.ticketing.outbox.domain;

import com.ticketing.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Entity
@Getter
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        uniqueConstraints = @UniqueConstraint(
                name = "uk_processed_message_consumer",
                columnNames = {"consumer_type", "message_id"}
        )
)
public class ProcessedMessage extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "consumer_type", nullable = false, length = 30)
    private OutboxConsumerType consumerType;

    @Column(name = "message_id", nullable = false, length = 36)
    private String messageId;

    public static ProcessedMessage of(OutboxConsumerType consumerType, String messageId) {
        return ProcessedMessage.builder()
                .consumerType(consumerType)
                .messageId(messageId)
                .build();
    }
}
