package com.example.ledger.reconciliation;

import java.util.UUID;

public record ReconciliationDiscrepancy(
        UUID transactionId,
        UUID accountId,
        String code,
        String detail
) {
    public static ReconciliationDiscrepancy forTransaction(
            UUID transactionId,
            String code,
            String detail
    ) {
        return new ReconciliationDiscrepancy(transactionId, null, code, detail);
    }

    public static ReconciliationDiscrepancy forAccount(
            UUID accountId,
            String code,
            String detail
    ) {
        return new ReconciliationDiscrepancy(null, accountId, code, detail);
    }
}
