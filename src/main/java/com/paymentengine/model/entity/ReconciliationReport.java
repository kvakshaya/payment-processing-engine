package com.paymentengine.model.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "reconciliation_reports")
@Getter
@Setter
@Builder
public class ReconciliationReport {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private LocalDate reportDate;
    private int totalInitiated;
    private int totalSettled;
    private int totalFailed;
    private int totalPending;
    private int discrepancyCount;
    private BigDecimal totalAmountInitiated;
    private BigDecimal totalAmountSettled;
    private String status;

    
}
