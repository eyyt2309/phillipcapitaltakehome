package com.example.ledger.reconciliation;

import java.math.BigDecimal;
import java.util.UUID;

public record LedgerAuditEntry(
        UUID transactionId,
        UUID accountId,
        String currency,
        BigDecimal amount
) {
}
