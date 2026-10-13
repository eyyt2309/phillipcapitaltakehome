package com.example.ledger.transaction;

public class ReversalNotAllowedException extends RuntimeException {

    public ReversalNotAllowedException() {
        super("Only original transfer transactions can be reversed.");
    }
}
