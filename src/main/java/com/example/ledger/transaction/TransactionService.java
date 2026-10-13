package com.example.ledger.transaction;

import com.example.ledger.account.Account;
import com.example.ledger.account.AccountNotFoundException;
import com.example.ledger.account.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class TransactionService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final RequestHasher requestHasher;

    public TransactionService(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            LedgerEntryRepository ledgerEntryRepository,
            RequestHasher requestHasher
    ) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.requestHasher = requestHasher;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferResult transfer(TransferRequest request, String idempotencyKey) {
        UUID sourceAccountId = Objects.requireNonNull(request.sourceAccountId());
        UUID destinationAccountId = Objects.requireNonNull(request.destinationAccountId());
        BigDecimal amount = request.amount().setScale(4, RoundingMode.UNNECESSARY);
        String requestHash = requestHasher.hash(request);

        var existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existingResult(existing.get(), requestHash);
        }

        if (sourceAccountId.equals(destinationAccountId)) {
            throw new SelfTransferException();
        }

        Account sourceMetadata = accountRepository.findById(sourceAccountId)
                .orElseThrow(() -> new AccountNotFoundException(sourceAccountId));
        Account destinationMetadata = accountRepository.findById(destinationAccountId)
                .orElseThrow(() -> new AccountNotFoundException(destinationAccountId));
        if (!sourceMetadata.getCurrency().equals(destinationMetadata.getCurrency())) {
            throw new CurrencyMismatchException(
                    sourceMetadata.getCurrency(),
                    destinationMetadata.getCurrency()
            );
        }

        UUID transactionId = Objects.requireNonNull(UUID.randomUUID());
        int claimed = transactionRepository.claimTransfer(
                transactionId,
                sourceAccountId,
                destinationAccountId,
                amount,
                sourceMetadata.getCurrency(),
                destinationMetadata.getCurrency(),
                idempotencyKey,
                requestHash
        );
        if (claimed == 0) {
            LedgerTransaction winner = transactionRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "Idempotency claim conflicted but no winning transaction was found."
                    ));
            return existingResult(winner, requestHash);
        }

        LedgerTransaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new IllegalStateException(
                        "The successful idempotency claim could not be loaded."
                ));

        boolean sourceLocksFirst = sourceAccountId.compareTo(destinationAccountId) < 0;
        UUID firstAccountId = sourceLocksFirst ? sourceAccountId : destinationAccountId;
        UUID secondAccountId = sourceLocksFirst ? destinationAccountId : sourceAccountId;
        Account firstAccount = accountRepository.findByIdForUpdate(firstAccountId)
                .orElse(null);
        Account secondAccount = accountRepository.findByIdForUpdate(secondAccountId)
                .orElse(null);
        if (firstAccount == null) {
            throw new AccountNotFoundException(firstAccountId);
        }
        if (secondAccount == null) {
            throw new AccountNotFoundException(secondAccountId);
        }

        Account sourceAccount = firstAccount.getId().equals(sourceAccountId)
                ? firstAccount
                : secondAccount;
        Account destinationAccount = firstAccount.getId().equals(destinationAccountId)
                ? firstAccount
                : secondAccount;

        if (!sourceAccount.getCurrency().equals(destinationAccount.getCurrency())) {
            throw new CurrencyMismatchException(
                    sourceAccount.getCurrency(),
                    destinationAccount.getCurrency()
            );
        }

        if (sourceAccount.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException();
        }

        sourceAccount.setBalance(sourceAccount.getBalance().subtract(amount));
        destinationAccount.setBalance(destinationAccount.getBalance().add(amount));

        ledgerEntryRepository.save(
                new LedgerEntry(UUID.randomUUID(), transaction, sourceAccount, amount.negate())
        );
        ledgerEntryRepository.save(
                new LedgerEntry(UUID.randomUUID(), transaction, destinationAccount, amount)
        );

        return new TransferResult(toResponse(transaction), false);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferResult reverse(UUID originalTransactionId, String idempotencyKey) {
        String requestHash = requestHasher.hashReversal(originalTransactionId);
        var existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existingResult(existing.get(), requestHash);
        }

        LedgerTransaction original = transactionRepository.findWithAccountsById(originalTransactionId)
                .orElseThrow(() -> new TransactionNotFoundException(originalTransactionId));
        if (original.getTransactionType() != TransactionType.TRANSFER) {
            throw new ReversalNotAllowedException();
        }

        UUID reversalId = UUID.randomUUID();
        int claimed = transactionRepository.claimReversal(
                reversalId,
                originalTransactionId,
                idempotencyKey,
                requestHash
        );
        if (claimed == 0) {
            LedgerTransaction winner = transactionRepository.findByIdempotencyKey(idempotencyKey)
                    .orElse(null);
            if (winner != null) {
                return existingResult(winner, requestHash);
            }
            throw new DuplicateReversalException();
        }

        LedgerTransaction reversal = transactionRepository.findById(reversalId)
                .orElseThrow(() -> new IllegalStateException(
                        "The successful reversal claim could not be loaded."
                ));
        List<LedgerEntryEffect> originalEffects =
                ledgerEntryRepository.findAllEffectsByTransactionId(originalTransactionId);
        if (originalEffects.isEmpty()) {
            throw new IllegalStateException("The original transfer has no ledger entries.");
        }

        Map<UUID, BigDecimal> balanceChanges = new TreeMap<>();
        for (LedgerEntryEffect effect : originalEffects) {
            balanceChanges.merge(effect.accountId(), effect.amount().negate(), BigDecimal::add);
        }

        Map<UUID, Account> lockedAccounts = new TreeMap<>();
        for (UUID accountId : balanceChanges.keySet()) {
            Account account = accountRepository.findByIdForUpdate(accountId)
                    .orElseThrow(() -> new AccountNotFoundException(accountId));
            lockedAccounts.put(accountId, account);
        }

        for (Map.Entry<UUID, BigDecimal> change : balanceChanges.entrySet()) {
            Account account = lockedAccounts.get(change.getKey());
            BigDecimal reversedBalance = account.getBalance().add(change.getValue());
            if (reversedBalance.compareTo(BigDecimal.ZERO) < 0) {
                throw new InsufficientFundsException();
            }
            account.setBalance(reversedBalance);
        }

        List<LedgerEntry> reversedEntries = new ArrayList<>(originalEffects.size());
        for (LedgerEntryEffect originalEffect : originalEffects) {
            Account account = lockedAccounts.get(originalEffect.accountId());
            reversedEntries.add(new LedgerEntry(
                    UUID.randomUUID(),
                    reversal,
                    account,
                    originalEffect.amount().negate()
            ));
        }
        ledgerEntryRepository.saveAll(reversedEntries);

        return new TransferResult(toResponse(reversal), false);
    }

    private TransferResult existingResult(LedgerTransaction transaction, String requestHash) {
        if (!transaction.getRequestHash().equals(requestHash)) {
            throw new IdempotencyConflictException();
        }
        return new TransferResult(toResponse(transaction), true);
    }

    private TransactionResponse toResponse(LedgerTransaction transaction) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getTransactionType(),
                "POSTED",
                transaction.getReversalOf(),
                transaction.getSourceAccount().getId(),
                transaction.getDestinationAccount().getId(),
                transaction.getSourceAmount(),
                transaction.getDestinationAmount(),
                transaction.getSourceCurrency(),
                transaction.getDestinationCurrency(),
                transaction.getCreatedAt()
        );
    }
}
