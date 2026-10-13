package com.example.ledger.account;

import com.example.ledger.transaction.TransactionType;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountTransactionItem(
        UUID transactionId,
        TransactionType type,
        @JsonSerialize(using = ToStringSerializer.class)
        BigDecimal amount,
        String currency,
        Instant createdAt,
        UUID reversalOf
) {
}
