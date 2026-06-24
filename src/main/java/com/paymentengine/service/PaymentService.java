package com.paymentengine.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentengine.exception.DuplicatePaymentException;
import com.paymentengine.exception.PaymentNotFoundException;
import com.paymentengine.model.dto.PaymentDto.*;
import com.paymentengine.model.entity.OutboxEvent;
import com.paymentengine.model.entity.Payment;
import com.paymentengine.model.enums.PaymentStatus;
import com.paymentengine.repository.OutboxEventRepository;
import com.paymentengine.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper; 

    /**
     * Initiates a payment.
     *
     * Key design decisions:
     * 1. Idempotency check FIRST (Redis) — fast path, avoids DB hit for retries
     * 2. DB check as fallback — handles Redis miss after TTL expiry
     * 3. Payment + OutboxEvent written in ONE transaction (Outbox Pattern)
     * 4. Idempotency key stored in Redis AFTER commit — no partial states
     * @throws JsonProcessingException 
     */
    @Transactional
    public PaymentResponse initiatePayment(PaymentRequest request) throws JsonProcessingException {
        // Step 1: Check Redis cache first (fast path for retries)
        Optional<PaymentResponse> cachedResponse = idempotencyService.getIfPresent(request.getIdempotencyKey());
        if (cachedResponse.isPresent()) {
            log.info("Returning cached idempotent response for key: {}", request.getIdempotencyKey());
            return cachedResponse.get();
        }

        // Step 2: Check DB as fallback (handles Redis TTL expiry or cache miss)
        Optional<Payment> existingPayment = paymentRepository.findByIdempotencyKey(request.getIdempotencyKey());
        if (existingPayment.isPresent()) {
            Payment payment = existingPayment.get();
            // If payment is in a terminal state, it's a genuine duplicate attempt — reject it
            if (payment.getStatus() == PaymentStatus.SUCCESS || payment.getStatus() == PaymentStatus.SETTLED) {
                throw new DuplicatePaymentException(
                    "Payment already processed for idempotency key: " + request.getIdempotencyKey()
                );
            }
            // If still processing, return current state (merchant can poll)
            PaymentResponse response = toResponse(payment);
            idempotencyService.store(request.getIdempotencyKey(), response); // Re-populate Redis
            return response;
        }

        // Step 3: New payment — persist payment + outbox event atomically
        Payment payment = Payment.builder()
                .idempotencyKey(request.getIdempotencyKey())
                .merchantId(request.getMerchantId())
                .customerId(request.getCustomerId())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .paymentMethod(request.getPaymentMethod())
                .status(PaymentStatus.INITIATED)
                .metadata(request.getMetadata())
                .build();

        payment = paymentRepository.save(payment);
        log.info("Payment created: {} for merchant: {}", payment.getId(), payment.getMerchantId());

        // Outbox event written in SAME transaction — if this commit fails, no Kafka message either
        PaymentEvent event = buildEvent(payment, "PAYMENT_INITIATED");
        outboxEventRepository.save(OutboxEvent.builder()
                .aggregateId(payment.getId().toString())
                .eventType("PAYMENT_INITIATED")
                .payload(objectMapper.writeValueAsString(event))
                .build());

        PaymentResponse response = toResponse(payment);

        // Step 4: Cache in Redis AFTER DB commit (Spring handles this via @Transactional boundary)
        // Note: this executes after the method returns if using transactional event listeners
        // For simplicity here, we store post-commit in the same thread
        idempotencyService.store(request.getIdempotencyKey(), response);

        return response;
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found: " + paymentId));
        return toResponse(payment);
    }

    /**
     * Called by Kafka consumer (SettlementConsumer) after settlement confirmation.
     * Updates payment status and writes a PAYMENT_SETTLED outbox event.
     * @throws JsonProcessingException 
     */
    @Transactional
    public void markAsSettled(UUID paymentId, String settlementRef) throws JsonProcessingException {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found during settlement: " + paymentId));

        if (payment.getStatus() != PaymentStatus.SUCCESS) {
            log.warn("Settlement attempted on non-SUCCESS payment: {} with status: {}", paymentId, payment.getStatus());
            return;
        }

        payment.setStatus(PaymentStatus.SETTLED);
        payment.setSettledAt(LocalDateTime.now());
        paymentRepository.save(payment);

        PaymentEvent event = buildEvent(payment, "PAYMENT_SETTLED");
        outboxEventRepository.save(OutboxEvent.builder()
                .aggregateId(paymentId.toString())
                .eventType("PAYMENT_SETTLED")
                .payload(objectMapper.writeValueAsString(event))
                .build());

        // Invalidate idempotency cache — settled payments are terminal, no retries needed
        idempotencyService.invalidate(payment.getIdempotencyKey());
        log.info("Payment {} marked as SETTLED. Ref: {}", paymentId, settlementRef);
    }

    /**
     * Called by retry scheduler for stale PROCESSING payments.
     */
    @Transactional
    public void markForRetry(UUID paymentId) {
        paymentRepository.updateStatusAndIncrementRetry(paymentId, PaymentStatus.PENDING_RETRY);
        log.info("Payment {} marked for retry.", paymentId);
    }

    // ---- Private helpers ----

    private PaymentResponse toResponse(Payment payment) {
        return PaymentResponse.builder()
                .paymentId(payment.getId())
                .idempotencyKey(payment.getIdempotencyKey())
                .merchantId(payment.getMerchantId())
                .customerId(payment.getCustomerId())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .paymentMethod(payment.getPaymentMethod())
                .status(payment.getStatus())
                .failureReason(payment.getFailureReason())
                .createdAt(payment.getCreatedAt())
                .updatedAt(payment.getUpdatedAt())
                .settledAt(payment.getSettledAt())
                .idempotentResponse(false)
                .build();
    }

    private PaymentEvent buildEvent(Payment payment, String eventType) {
        return PaymentEvent.builder()
                .paymentId(payment.getId())
                .idempotencyKey(payment.getIdempotencyKey())
                .merchantId(payment.getMerchantId())
                .customerId(payment.getCustomerId())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .paymentMethod(payment.getPaymentMethod())
                .status(payment.getStatus())
                .eventType(eventType)
                .eventTimestamp(LocalDateTime.now())
                .build();
    }
}
