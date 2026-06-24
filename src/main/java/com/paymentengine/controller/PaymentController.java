package com.paymentengine.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.paymentengine.model.dto.PaymentDto.*;
import com.paymentengine.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Slf4j
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * POST /api/v1/payments
     *
     * Initiates a new payment. Idempotent — duplicate requests with the
     * same idempotency_key return the original response without re-processing.
     *
     * Returns 200 for idempotent responses (already processed).
     * Returns 201 for newly created payments.
     */
    
    @PostMapping
    public ResponseEntity<PaymentResponse> initiatePayment(@Valid @RequestBody PaymentRequest request) throws JsonProcessingException  {
        log.info("Payment initiation request received. MerchantId: {} IdempotencyKey: {}",
            request.getMerchantId(), request.getIdempotencyKey());

        PaymentResponse response = paymentService.initiatePayment(request);

        HttpStatus status = response.isIdempotentResponse() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }

    /**
     * GET /api/v1/payments/{paymentId}
     * Fetch payment status — merchants poll this after initiation.
     */
    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentResponse> getPayment(@PathVariable UUID paymentId) {
        return ResponseEntity.ok(paymentService.getPayment(paymentId));
    }
}
