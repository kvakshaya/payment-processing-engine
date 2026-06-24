package com.paymentengine.kafka.producer;

import com.paymentengine.model.dto.PaymentDto.PaymentEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventProducer {

    private final KafkaTemplate<String, PaymentEvent> kafkaTemplate;

    @Value("${app.kafka.topics.payment-initiated}")
    private String paymentInitiatedTopic;

    @Value("${app.kafka.topics.payment-settlement}")
    private String settlementTopic;

    @Value("${app.kafka.topics.payment-dlq}")
    private String dlqTopic;

    /**
     * Publishes a payment event to Kafka.
     * Key = paymentId ensures all events for a payment go to the same partition
     * (preserving ordering per payment).
     */
    public void publishPaymentEvent(PaymentEvent event) {
        String topic = resolveTopic(event.getEventType());
        String key = event.getPaymentId().toString();

        CompletableFuture<SendResult<String, PaymentEvent>> future =
            kafkaTemplate.send(topic, key, event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish event {} for payment {}. Sending to DLQ.",
                    event.getEventType(), event.getPaymentId(), ex);
                sendToDlq(event);
            } else {
                log.info("Published event {} for payment {} to topic {} partition {} offset {}",
                    event.getEventType(),
                    event.getPaymentId(),
                    result.getRecordMetadata().topic(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
            }
        });
    }

    /**
     * Dead Letter Queue: events that failed after all retries go here.
     * Ops team monitors DLQ for manual intervention.
     */
    private void sendToDlq(PaymentEvent event) {
        try {
            kafkaTemplate.send(dlqTopic, event.getPaymentId().toString(), event);
            log.warn("Event for payment {} sent to DLQ: {}", event.getPaymentId(), dlqTopic);
        } catch (Exception e) {
            // If even DLQ fails, the outbox table acts as the source of truth.
            // Outbox records remain unpublished and will be retried by OutboxPublisherScheduler.
            log.error("CRITICAL: DLQ publish also failed for payment {}. Outbox record preserved.",
                event.getPaymentId(), e);
        }
    }

    private String resolveTopic(String eventType) {
        return switch (eventType) {
            case "PAYMENT_INITIATED" -> paymentInitiatedTopic;
            case "PAYMENT_SETTLED"   -> settlementTopic;
            default                  -> paymentInitiatedTopic;
        };
    }
}
