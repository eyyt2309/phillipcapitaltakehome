package com.example.ledger.account;

import java.util.UUID;

public record AccountResponse(UUID accountId, String currency, String balance) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getCurrency(),
                account.getBalance().toPlainString()
        );
    }
}
