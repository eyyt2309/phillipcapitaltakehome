package com.example.ledger.reconciliation;

import java.time.LocalDate;
import java.util.List;

public record ReconciliationResponse(
        LocalDate asOfDate,
        boolean historicalReconstructionOnly,
        String status,
        List<AccountReconciliationResult> accountResults,
        List<CurrencyReconciliationTotal> currencyTotals,
        TransactionChecks transactionChecks
) {
}
