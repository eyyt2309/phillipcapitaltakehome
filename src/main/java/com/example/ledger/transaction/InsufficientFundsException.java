package com.example.ledger.transaction;

public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException() {
        super("The source account has insufficient funds.");
    }
}
