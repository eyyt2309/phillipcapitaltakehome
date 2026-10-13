package com.example.ledger.reconciliation;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountLedgerSnapshot(
        UUID accountId,
        String currency,
        BigDecimal openingBalance,
        BigDecimal storedBalance,
        BigDecimal ledgerEffect
) {
}
