package com.example.ledger.account;

import com.example.ledger.transaction.LedgerEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public AccountService(
            AccountRepository accountRepository,
            LedgerEntryRepository ledgerEntryRepository
    ) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(UUID accountId) {
        Account account = accountRepository.findById(Objects.requireNonNull(accountId))
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        return AccountResponse.from(account);
    }

    @Transactional(readOnly = true)
    public AccountTransactionHistoryResponse getTransactionHistory(UUID accountId) {
        accountRepository.findById(Objects.requireNonNull(accountId))
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        List<AccountTransactionItem> items =
                ledgerEntryRepository.findHistoryByAccountId(accountId);
        return new AccountTransactionHistoryResponse(items);
    }
}
