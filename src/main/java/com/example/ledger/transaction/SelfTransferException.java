package com.example.ledger.transaction;

public class SelfTransferException extends RuntimeException {

    public SelfTransferException() {
        super("Source and destination accounts must be different.");
    }
}
