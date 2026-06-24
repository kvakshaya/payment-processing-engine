package com.paymentengine.model.enums;

public enum PaymentStatus {
    INITIATED,      // Payment created, not yet sent to processor
    PROCESSING,     // Sent to payment processor, awaiting confirmation
    SUCCESS,        // Processor confirmed success
    FAILED,         // Processor returned failure
    SETTLED,        // Amount credited to merchant account
    REFUNDED,       // Refund processed
    PENDING_RETRY   // Failed but eligible for retry
}
