package com.example.ledger.transaction;

public class CurrencyMismatchException extends RuntimeException {

    public CurrencyMismatchException(String sourceCurrency, String destinationCurrency) {
        super("Same-currency transfer required; received "
                + sourceCurrency + " and " + destinationCurrency + ".");
    }
}
