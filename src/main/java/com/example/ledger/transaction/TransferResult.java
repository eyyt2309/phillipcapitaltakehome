package com.example.ledger.transaction;

public record TransferResult(TransactionResponse response, boolean replayed) {
}
