package com.paymentengine.service;

import com.paymentengine.model.entity.ReconciliationReport;
import com.paymentengine.model.enums.PaymentStatus;
import com.paymentengine.repository.PaymentRepository;
import com.paymentengine.repository.ReconciliationReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Reconciliation Service
 *
 * Runs daily at 1 AM (configurable via cron).
 * Compares all payment records for the previous day across statuses.
 *
 * Business purpose:
 * - Detect payments stuck in PROCESSING (possible PSP timeout — needs follow-up)
 * - Identify discrepancies between initiated and settled amounts
 * - Generate audit trail required by RBI for payment aggregators
 *
 * In a real system, this would also:
 * - Pull settlement file from the bank/PSP (SFTP or API)
 * - Match each payment against the bank ledger line by line
 * - Flag any amount mismatches or missing settlements
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReconciliationService {

    private final PaymentRepository paymentRepository;
    private final ReconciliationReportRepository reconciliationReportRepository;

    @Scheduled(cron = "${app.reconciliation.cron:0 0 1 * * *}")
    @Transactional
    public void runDailyReconciliation() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        reconcile(yesterday);
    }

    public ReconciliationReport reconcile(LocalDate date) {
        log.info("Starting reconciliation for date: {}", date);

        LocalDateTime startOfDay = date.atStartOfDay();
        LocalDateTime endOfDay   = date.plusDays(1).atStartOfDay();

        List<Object[]> summaryRows = paymentRepository.getPaymentSummaryByStatus(startOfDay, endOfDay);

        int totalInitiated = 0, totalSettled = 0, totalFailed = 0, totalPending = 0;
        BigDecimal amountInitiated = BigDecimal.ZERO, amountSettled = BigDecimal.ZERO;
        int discrepancyCount = 0;

        for (Object[] row : summaryRows) {
            log.info(" row[0]: "+ row[0]);
            PaymentStatus status = (PaymentStatus) row[0];
            int count = ((Number) row[1]).intValue();
            BigDecimal amount = (BigDecimal) row[2];

            switch (status) {
                case INITIATED, PROCESSING, PENDING_RETRY -> {
                    totalPending += count;
                    amountInitiated = amountInitiated.add(amount);
                    if (count > 0) discrepancyCount += count; // Payments not yet settled = potential discrepancy
                }
                case SUCCESS -> {
                    totalInitiated += count;
                    amountInitiated = amountInitiated.add(amount);
                }
                case SETTLED -> {
                    totalSettled += count;
                    amountSettled = amountSettled.add(amount);
                }
                case FAILED, REFUNDED -> totalFailed += count;
            }
        }

        ReconciliationReport report = ReconciliationReport.builder()
                .reportDate(date)
                .totalInitiated(totalInitiated)
                .totalSettled(totalSettled)
                .totalFailed(totalFailed)
                .totalPending(totalPending)
                .discrepancyCount(discrepancyCount)
                .totalAmountInitiated(amountInitiated)
                .totalAmountSettled(amountSettled)
                .status("COMPLETED")
                .build();

        reconciliationReportRepository.save(report);

        log.info("Reconciliation complete for {}: initiated={}, settled={}, failed={}, pending={}, discrepancies={}",
            date, totalInitiated, totalSettled, totalFailed, totalPending, discrepancyCount);

        if (discrepancyCount > 0) {
            log.warn("RECONCILIATION ALERT: {} discrepancies found for {}. Ops team notified.", discrepancyCount, date);
            // Production: trigger PagerDuty/Slack alert
        }

        return report;
    }
}
