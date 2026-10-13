package com.example.ledger.reconciliation;

import java.util.List;

public record TransactionChecks(int checked, List<ReconciliationDiscrepancy> discrepancies) {
}
