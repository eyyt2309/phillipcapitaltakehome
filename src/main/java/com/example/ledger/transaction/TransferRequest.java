package com.example.ledger.transaction;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record TransferRequest(
        @NotNull UUID sourceAccountId,
        @NotNull UUID destinationAccountId,
        @NotNull
        @DecimalMin(value = "0.0000", inclusive = false)
        @Digits(integer = 15, fraction = 4)
        BigDecimal amount
) {
}
