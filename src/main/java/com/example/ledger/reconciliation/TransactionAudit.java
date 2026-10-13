package com.example.ledger.reconciliation;

import com.example.ledger.transaction.TransactionType;

import java.math.BigDecimal;
import java.util.UUID;

public record TransactionAudit(
        UUID transactionId,
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal sourceAmount,
        String sourceCurrency,
        BigDecimal destinationAmount,
        String destinationCurrency,
        BigDecimal exchangeRate,
        TransactionType type,
        UUID reversalOf
) {
}
