package com.paymentengine.controller;

import com.paymentengine.service.ReconciliationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final ReconciliationService reconciliationService;

    @PostMapping("/reconcile")
    public String triggerReconciliation(@RequestParam(defaultValue = "today") String date) {
        LocalDate reconcileDate = date.equals("today") 
            ? LocalDate.now() 
            : LocalDate.parse(date);
        reconciliationService.reconcile(reconcileDate);
        return "Reconciliation triggered for: " + reconcileDate;
    }
}