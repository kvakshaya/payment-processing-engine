package com.paymentengine.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Outbox Pattern: Instead of publishing to Kafka directly (dual-write risk),
 * we write an event row in the SAME DB transaction as the payment record.
 * A separate scheduler then reads unpublished events and publishes to Kafka.
 *
 * This guarantees: if DB commit succeeds → event will eventually be published.
 * No event is lost even if Kafka is down at the time of payment creation.
 */
@Entity
@Table(name = "outbox_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "aggregate_id", nullable = false, length = 50)
    private String aggregateId;  // Payment ID

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;    // PAYMENT_INITIATED, PAYMENT_SETTLED, etc.

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(nullable = false)
    @Builder.Default
    private boolean published = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
