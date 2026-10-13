package com.example.ledger.reconciliation;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;

public record CurrencyReconciliationTotal(
        String currency,
        @JsonSerialize(using = ToStringSerializer.class)
        BigDecimal openingBalanceTotal,
        @JsonSerialize(using = ToStringSerializer.class)
        BigDecimal ledgerEffectTotal,
        @JsonSerialize(using = ToStringSerializer.class)
        BigDecimal expectedBalanceTotal,
        @JsonSerialize(using = ToStringSerializer.class)
        BigDecimal storedBalanceTotal,
        @JsonSerialize(using = ToStringSerializer.class)
        BigDecimal difference,
        String status
) {
}
