package com.example.ledger.account;

import java.util.UUID;

public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(UUID accountId) {
        super("Account " + accountId + " was not found.");
    }
}
