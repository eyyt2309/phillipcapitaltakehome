package com.example.ledger.transaction;

public class DuplicateReversalException extends RuntimeException {

    public DuplicateReversalException() {
        super("The transaction has already been reversed.");
    }
}
