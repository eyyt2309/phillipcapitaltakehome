package com.example.ledger.reconciliation;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountReconciliationResult(
        UUID accountId,
        String currency,
        @JsonSerialize(using = ToStringSerializer.class)
        BigDecimal openingBalance,
        @JsonSerialize(using = ToStringSerializer.class)
        BigDecimal ledgerEffect,
        @JsonSerialize(using = ToStringSerializer.class)
        BigDecimal expectedBalance,
        @JsonSerialize(using = ToStringSerializer.class)
        BigDecimal storedBalance,
        @JsonSerialize(using = ToStringSerializer.class)
        BigDecimal difference,
        String status
) {
}
