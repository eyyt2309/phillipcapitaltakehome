package com.example.ledger.transaction;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
        UUID transactionId,
        String status,
        UUID sourceAccountId,
        UUID destinationAccountId,
        @JsonSerialize(using = ToStringSerializer.class)
        BigDecimal sourceAmount,
        @JsonSerialize(using = ToStringSerializer.class)
        BigDecimal destinationAmount,
        String sourceCurrency,
        String destinationCurrency,
        Instant createdAt
) {
}
