package com.example.ledger.transaction;

public class UnsupportedCurrencyPairException extends RuntimeException {

    public UnsupportedCurrencyPairException(String sourceCurrency, String destinationCurrency) {
        super("Transfers from " + sourceCurrency + " to " + destinationCurrency
                + " are not supported.");
    }
}
