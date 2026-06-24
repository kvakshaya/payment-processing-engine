package com.paymentengine.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentengine.kafka.producer.PaymentEventProducer;
import com.paymentengine.model.dto.PaymentDto.PaymentEvent;
import com.paymentengine.model.entity.OutboxEvent;
import com.paymentengine.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox Publisher Scheduler
 *
 * Polls the outbox_events table every 5 seconds for unpublished events
 * and publishes them to Kafka.
 *
 * Why this exists:
 * We can't publish to Kafka inside a DB transaction (dual-write problem).
 * If we publish to Kafka and then the DB commit fails, we get a ghost event.
 * If we commit to DB and then Kafka publish fails, we lose the event.
 *
 * The Outbox Pattern solves this:
 * 1. Write payment + outbox event atomically in ONE DB transaction
 * 2. This scheduler reads the outbox table and publishes to Kafka
 * 3. After successful publish, marks the outbox record as published
 *
 * This gives us at-least-once delivery guarantee.
 * Consumers must handle duplicates (they check payment status before processing).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisherScheduler {

    private final OutboxEventRepository outboxEventRepository;
    private final PaymentEventProducer eventProducer;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 5000) // Poll every 5 seconds
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> unpublishedEvents = outboxEventRepository.findByPublishedFalseOrderByCreatedAtAsc();

        if (unpublishedEvents.isEmpty()) {
             log.info("Scheduler ran — no unpublished events found.");
            return;
        }

        log.info("Found {} unpublished outbox events. Publishing...", unpublishedEvents.size());

        for (OutboxEvent outboxEvent : unpublishedEvents) {
            try {
                PaymentEvent paymentEvent = objectMapper.readValue(outboxEvent.getPayload(), PaymentEvent.class);
                eventProducer.publishPaymentEvent(paymentEvent);
                outboxEventRepository.markAsPublished(outboxEvent.getId(), LocalDateTime.now());
                log.debug("Outbox event {} published and marked done.", outboxEvent.getId());

            } catch (Exception e) {
                // Skip this event — will retry on next poll cycle
                // Prevents one bad event from blocking others
                log.error("Failed to publish outbox event {}. Will retry in 5s.", outboxEvent.getId(), e);
            }
        }
    }
}
