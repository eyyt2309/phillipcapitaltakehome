package com.example.ledger.account;

import java.util.List;

public record AccountTransactionHistoryResponse(List<AccountTransactionItem> items) {
}
