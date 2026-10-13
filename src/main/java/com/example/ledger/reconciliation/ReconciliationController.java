package com.example.ledger.reconciliation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.DateTimeException;

@RestController
@RequestMapping("/reconciliation")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @GetMapping
    public ReconciliationResponse reconcile(
            @RequestParam(name = "asOfDate", required = false) String asOfDate
    ) {
        if (asOfDate == null) {
            return reconciliationService.reconcile(null);
        }
        try {
            return reconciliationService.reconcile(LocalDate.parse(asOfDate));
        } catch (DateTimeException exception) {
            throw new InvalidAsOfDateException();
        }
    }
}
