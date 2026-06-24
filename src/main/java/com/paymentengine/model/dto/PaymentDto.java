package com.paymentengine.model.dto;

import com.paymentengine.model.enums.PaymentMethod;
import com.paymentengine.model.enums.PaymentStatus;
import jakarta.validation.constraints.*;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

public class PaymentDto {

    /**
     * Request DTO — what the merchant sends us
     */
    @Data
    @Builder
    public static class PaymentRequest {

        @NotBlank(message = "Idempotency key is required")
        @Size(min = 8, max = 64, message = "Idempotency key must be between 8 and 64 characters")
        private String idempotencyKey;   // Merchant generates this UUID/hash per payment attempt

        @NotBlank(message = "Merchant ID is required")
        private String merchantId;

        @NotBlank(message = "Customer ID is required")
        private String customerId;

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
        @Digits(integer = 13, fraction = 2, message = "Invalid amount format")
        private BigDecimal amount;

        @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be a 3-letter ISO code")
        @Builder.Default
        private String currency = "INR";

        @NotNull(message = "Payment method is required")
        private PaymentMethod paymentMethod;

        private Map<String, Object> metadata;  // UPI VPA, card token, etc.
    }

    /**
     * Response DTO — what we return to the merchant
     */
    @Data
    @Builder
    public static class PaymentResponse {
        private UUID paymentId;
        private String idempotencyKey;
        private String merchantId;
        private String customerId;
        private BigDecimal amount;
        private String currency;
        private PaymentMethod paymentMethod;
        private PaymentStatus status;
        private String failureReason;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private LocalDateTime settledAt;
        private boolean idempotentResponse;  // true = returned cached result, not a new payment
    }

    /**
     * Event payload published to Kafka
     */
    @Data
    @Builder
    public static class PaymentEvent {
        private UUID paymentId;
        private String idempotencyKey;
        private String merchantId;
        private String customerId;
        private BigDecimal amount;
        private String currency;
        private PaymentMethod paymentMethod;
        private PaymentStatus status;
        private String eventType;
        private LocalDateTime eventTimestamp;
    }
}
