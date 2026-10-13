package com.example.ledger.reconciliation;

public class InvalidAsOfDateException extends RuntimeException {

    public InvalidAsOfDateException() {
        super("asOfDate must use YYYY-MM-DD format.");
    }
}
