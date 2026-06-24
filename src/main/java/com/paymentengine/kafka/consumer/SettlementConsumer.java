package com.paymentengine.kafka.consumer;

import com.paymentengine.model.dto.PaymentDto.PaymentEvent;
import com.paymentengine.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class SettlementConsumer {

    private final PaymentService paymentService;

    /**
     * Consumes settlement confirmation events from the payment processor.
     * @throws Exception 
     *
     * @RetryableTopic:
     * - Retries 3 times with exponential backoff (1s → 2s → 4s)
     * - After max retries, routes to payment.settlement-dlt (Dead Letter Topic)
     * - This is NON-BLOCKING retry — retries go to separate retry topics,
     *   so the main topic consumer keeps processing other messages
     *
     * Manual acknowledgment (enable-auto-commit: false):
     * - We only commit offset AFTER successful processing
     * - If processing fails, message is not committed → will be redelivered
     * - Prevents message loss on consumer crash mid-processing
     */
    @RetryableTopic(
        attempts = "3",
        backoff = @Backoff(delay = 1000, multiplier = 2),
        topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
        dltTopicSuffix = "-dlt"
    )
    @KafkaListener(
        topics = "${app.kafka.topics.payment-settlement}",
        groupId = "payment-settlement-group",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeSettlementEvent(@Payload PaymentEvent event, Acknowledgment acknowledgment) throws Exception {
        log.info("Received settlement event for payment: {} status: {}",
            event.getPaymentId(), event.getStatus());

        try {
            paymentService.markAsSettled(event.getPaymentId(), generateSettlementRef(event));
            acknowledgment.acknowledge(); // Commit offset only on success
            log.info("Settlement processed and offset committed for payment: {}", event.getPaymentId());

        } catch (Exception e) {
            // Do NOT acknowledge — message will be retried by @RetryableTopic
            log.error("Failed to process settlement for payment: {}. Will retry.",
                event.getPaymentId(), e);
            throw e; // Re-throw to trigger retry mechanism
        }
    }

    /**
     * Dead Letter Topic handler — called when all retries are exhausted.
     * Logs for ops team to investigate manually.
     */
    @KafkaListener(
        topics = "${app.kafka.topics.payment-settlement}-dlt",
        groupId = "payment-settlement-dlt-group"
    )
    public void handleDeadLetter(@Payload PaymentEvent event, Acknowledgment acknowledgment) {
        log.error("DEAD LETTER: Settlement failed after all retries for payment: {}. Manual intervention required.",
            event.getPaymentId());
        // In production: send PagerDuty alert, write to ops dashboard, create incident ticket
        acknowledgment.acknowledge();
    }

    private String generateSettlementRef(PaymentEvent event) {
        // In production: extract from actual PSP response metadata
        return "SETTLE-" + event.getPaymentId().toString().substring(0, 8).toUpperCase();
    }
}
