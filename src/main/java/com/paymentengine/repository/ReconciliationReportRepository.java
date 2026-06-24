package com.paymentengine.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

import com.paymentengine.model.entity.ReconciliationReport;

public interface ReconciliationReportRepository extends JpaRepository<ReconciliationReport, UUID> {

}
