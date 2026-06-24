package com.paymentengine.repository;

import com.paymentengine.model.entity.Payment;
import com.paymentengine.model.enums.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    List<Payment> findByMerchantIdAndStatus(String merchantId, PaymentStatus status);

    // For reconciliation: get all payments created on a specific date
    @Query("""
        SELECT p FROM Payment p
        WHERE p.createdAt >= :startOfDay
        AND p.createdAt < :endOfDay
        ORDER BY p.createdAt ASC
    """)
    List<Payment> findPaymentsByDate(
        @Param("startOfDay") LocalDateTime startOfDay,
        @Param("endOfDay") LocalDateTime endOfDay
    );

    // For retry job: fetch payments stuck in PROCESSING state beyond timeout
    @Query("""
        SELECT p FROM Payment p
        WHERE p.status = 'PROCESSING'
        AND p.updatedAt < :timeoutThreshold
        AND p.retryCount < :maxRetries
    """)
    List<Payment> findStaleProcessingPayments(
        @Param("timeoutThreshold") LocalDateTime timeoutThreshold,
        @Param("maxRetries") int maxRetries
    );

    @Modifying
    @Query("UPDATE Payment p SET p.status = :status, p.retryCount = p.retryCount + 1 WHERE p.id = :id")
    void updateStatusAndIncrementRetry(@Param("id") UUID id, @Param("status") PaymentStatus status);

    // Aggregates for reconciliation report
    @Query("""
        SELECT p.status, COUNT(p), COALESCE(SUM(p.amount), 0)
        FROM Payment p
        WHERE p.createdAt >= :startOfDay AND p.createdAt < :endOfDay
        GROUP BY p.status
    """)
    List<Object[]> getPaymentSummaryByStatus(
        @Param("startOfDay") LocalDateTime startOfDay,
        @Param("endOfDay") LocalDateTime endOfDay
    );
}
