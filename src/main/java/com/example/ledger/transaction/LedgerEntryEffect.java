package com.example.ledger.transaction;

import java.math.BigDecimal;
import java.util.UUID;

public record LedgerEntryEffect(UUID accountId, BigDecimal amount) {
}
