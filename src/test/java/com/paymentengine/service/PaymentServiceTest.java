package com.paymentengine.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.paymentengine.exception.DuplicatePaymentException;
import com.paymentengine.model.dto.PaymentDto.*;
import com.paymentengine.model.entity.Payment;
import com.paymentengine.model.enums.PaymentMethod;
import com.paymentengine.model.enums.PaymentStatus;
import com.paymentengine.repository.OutboxEventRepository;
import com.paymentengine.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private IdempotencyService idempotencyService;

    @InjectMocks private PaymentService paymentService;

    private PaymentRequest validRequest;
    private Payment existingPayment;

    @BeforeEach
    void setUp() {
        validRequest = PaymentRequest.builder()
                .idempotencyKey("test-idem-key-001")
                .merchantId("merchant-123")
                .customerId("customer-456")
                .amount(new BigDecimal("500.00"))
                .currency("INR")
                .paymentMethod(PaymentMethod.UPI)
                .build();

        existingPayment = Payment.builder()
                .id(UUID.randomUUID())
                .idempotencyKey("test-idem-key-001")
                .merchantId("merchant-123")
                .customerId("customer-456")
                .amount(new BigDecimal("500.00"))
                .currency("INR")
                .paymentMethod(PaymentMethod.UPI)
                .status(PaymentStatus.INITIATED)
                .build();
        existingPayment.setCreatedAt(LocalDateTime.now());
        existingPayment.setUpdatedAt(LocalDateTime.now());
    }

    @Test
    @DisplayName("New payment: should create payment and outbox event")
    void initiatePayment_newRequest_shouldCreatePaymentAndOutboxEvent() throws JsonProcessingException {
        when(idempotencyService.getIfPresent(any())).thenReturn(Optional.empty());
        when(paymentRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(paymentRepository.save(any())).thenReturn(existingPayment);

        PaymentResponse response = paymentService.initiatePayment(validRequest);

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(PaymentStatus.INITIATED);
        assertThat(response.isIdempotentResponse()).isFalse();

        verify(paymentRepository).save(any(Payment.class));
        verify(outboxEventRepository).save(any());
        verify(idempotencyService).store(eq("test-idem-key-001"), any());
    }

    @Test
    @DisplayName("Duplicate request: should return cached response from Redis")
    void initiatePayment_duplicateRequest_shouldReturnCachedResponse() throws JsonProcessingException {
        PaymentResponse cachedResponse = PaymentResponse.builder()
                .paymentId(existingPayment.getId())
                .idempotencyKey("test-idem-key-001")
                .status(PaymentStatus.INITIATED)
                .idempotentResponse(true)
                .build();

        when(idempotencyService.getIfPresent("test-idem-key-001")).thenReturn(Optional.of(cachedResponse));

        PaymentResponse response = paymentService.initiatePayment(validRequest);

        assertThat(response.isIdempotentResponse()).isTrue();
        // Verify no DB write happened — Redis served the response
        verify(paymentRepository, never()).save(any());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("Terminal duplicate: should throw DuplicatePaymentException")
    void initiatePayment_terminalDuplicate_shouldThrowException() {
        existingPayment.setStatus(PaymentStatus.SUCCESS);

        when(idempotencyService.getIfPresent(any())).thenReturn(Optional.empty());
        when(paymentRepository.findByIdempotencyKey(any())).thenReturn(Optional.of(existingPayment));

        assertThatThrownBy(() -> paymentService.initiatePayment(validRequest))
                .isInstanceOf(DuplicatePaymentException.class)
                .hasMessageContaining("already processed");

        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("Settlement: should update status to SETTLED and invalidate idempotency key")
    void markAsSettled_shouldUpdateStatusAndInvalidateKey() throws JsonProcessingException {
        existingPayment.setStatus(PaymentStatus.SUCCESS);

        when(paymentRepository.findById(existingPayment.getId())).thenReturn(Optional.of(existingPayment));
        when(paymentRepository.save(any())).thenReturn(existingPayment);

        paymentService.markAsSettled(existingPayment.getId(), "SETTLE-REF-001");

        verify(paymentRepository).save(argThat(p -> p.getStatus() == PaymentStatus.SETTLED));
        verify(idempotencyService).invalidate(existingPayment.getIdempotencyKey());
        verify(outboxEventRepository).save(any());
    }
}
