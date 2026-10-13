package com.example.ledger.reconciliation;

import com.example.ledger.transaction.TransactionType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ReconciliationService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(4);
    private static final String OK = "OK";
    private static final String DISCREPANCY = "DISCREPANCY";

    private final ReconciliationRepository reconciliationRepository;

    public ReconciliationService(ReconciliationRepository reconciliationRepository) {
        this.reconciliationRepository = reconciliationRepository;
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public ReconciliationResponse reconcile(LocalDate asOfDate) {
        boolean historical = asOfDate != null;
        var cutoff = historical
                ? asOfDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
                : null;

        List<AccountLedgerSnapshot> snapshots = historical
                ? reconciliationRepository.findHistoricalAccountSnapshots(cutoff)
                : reconciliationRepository.findCurrentAccountSnapshots();
        List<AccountReconciliationResult> accountResults = new ArrayList<>(snapshots.size());
        List<ReconciliationDiscrepancy> discrepancies = new ArrayList<>();
        Map<String, CurrencyTotalsBuilder> totalsByCurrency = new LinkedHashMap<>();

        for (AccountLedgerSnapshot snapshot : snapshots) {
            BigDecimal openingBalance = scale(snapshot.openingBalance());
            BigDecimal ledgerEffect = scale(snapshot.ledgerEffect());
            BigDecimal expectedBalance = scale(openingBalance.add(ledgerEffect));
            BigDecimal storedBalance = historical ? null : scale(snapshot.storedBalance());
            BigDecimal difference = historical ? null : scale(storedBalance.subtract(expectedBalance));
            boolean accountMatches = historical || difference.compareTo(ZERO) == 0;
            accountResults.add(new AccountReconciliationResult(
                    snapshot.accountId(),
                    snapshot.currency(),
                    openingBalance,
                    ledgerEffect,
                    expectedBalance,
                    storedBalance,
                    difference,
                    accountMatches ? OK : DISCREPANCY
            ));

            CurrencyTotalsBuilder totals = totalsByCurrency.computeIfAbsent(
                    snapshot.currency(),
                    ignored -> new CurrencyTotalsBuilder()
            );
            totals.openingBalance = totals.openingBalance.add(openingBalance);
            totals.ledgerEffect = totals.ledgerEffect.add(ledgerEffect);
            totals.expectedBalance = totals.expectedBalance.add(expectedBalance);
            if (!historical) {
                totals.storedBalance = totals.storedBalance.add(storedBalance);
            }

            if (!accountMatches) {
                totals.hasDiscrepancy = true;
                discrepancies.add(ReconciliationDiscrepancy.forAccount(
                        snapshot.accountId(),
                        "ACCOUNT_BALANCE_MISMATCH",
                        "Stored balance %s differs from ledger-derived expected balance %s."
                                .formatted(storedBalance.toPlainString(),
                                        expectedBalance.toPlainString())
                ));
            }
        }

        List<TransactionAudit> transactions = reconciliationRepository.findTransactions(cutoff);
        List<UUID> transactionIds = transactions.stream()
                .map(TransactionAudit::transactionId)
                .toList();
        List<LedgerAuditEntry> entries = reconciliationRepository.findEntries(transactionIds, cutoff);
        Map<UUID, List<LedgerAuditEntry>> entriesByTransaction = entries.stream()
                .collect(Collectors.groupingBy(
                        LedgerAuditEntry::transactionId,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        List<UUID> originalIds = transactions.stream()
                .filter(transaction -> transaction.type() == TransactionType.REVERSAL)
                .map(TransactionAudit::reversalOf)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, TransactionAudit> originals = reconciliationRepository
                .findTransactionsByIds(originalIds)
                .stream()
                .collect(Collectors.toMap(TransactionAudit::transactionId, Function.identity()));

        for (TransactionAudit transaction : transactions) {
            List<LedgerAuditEntry> transactionEntries = entriesByTransaction.getOrDefault(
                    transaction.transactionId(),
                    List.of()
            );
            if (transaction.type() == TransactionType.REVERSAL) {
                checkReversal(transaction, transactionEntries, originals, entriesByTransaction, discrepancies);
            } else {
                checkTransfer(transaction, transactionEntries, discrepancies);
            }
        }

        List<CurrencyReconciliationTotal> currencyTotals = totalsByCurrency.entrySet().stream()
                .map(entry -> {
                    CurrencyTotalsBuilder totals = entry.getValue();
                    BigDecimal opening = scale(totals.openingBalance);
                    BigDecimal effect = scale(totals.ledgerEffect);
                    BigDecimal expected = scale(totals.expectedBalance);
                    BigDecimal stored = historical ? null : scale(totals.storedBalance);
                    BigDecimal difference = historical ? null : scale(stored.subtract(expected));
                    return new CurrencyReconciliationTotal(
                            entry.getKey(),
                            opening,
                            effect,
                            expected,
                            stored,
                            difference,
                            historical || (!totals.hasDiscrepancy
                                    && difference.compareTo(ZERO) == 0) ? OK : DISCREPANCY
                    );
                })
                .toList();

        return new ReconciliationResponse(
                asOfDate,
                historical,
                discrepancies.isEmpty() ? OK : DISCREPANCY,
                accountResults,
                currencyTotals,
                new TransactionChecks(transactions.size(), discrepancies)
        );
    }

    private void checkTransfer(
            TransactionAudit transaction,
            List<LedgerAuditEntry> entries,
            List<ReconciliationDiscrepancy> discrepancies
    ) {
        if (entries.size() != 2) {
            discrepancy(
                    discrepancies,
                    transaction,
                    "INVALID_ENTRY_COUNT",
                    "Expected exactly two ledger entries but found %d.".formatted(entries.size())
            );
        }

        BigDecimal sourceEntry = amountFor(entries, transaction.sourceAccountId());
        BigDecimal destinationEntry = amountFor(entries, transaction.destinationAccountId());
        if (transaction.sourceCurrency().equals(transaction.destinationCurrency())) {
            BigDecimal net = entries.stream()
                    .map(LedgerAuditEntry::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (net.compareTo(BigDecimal.ZERO) != 0) {
                discrepancy(
                        discrepancies,
                        transaction,
                        "SAME_CURRENCY_NOT_BALANCED",
                        "Same-currency ledger entries have net amount %s."
                                .formatted(net.toPlainString())
                );
            }
            if (sourceEntry.compareTo(transaction.sourceAmount().negate()) != 0
                    || destinationEntry.compareTo(transaction.destinationAmount()) != 0
                    || entries.stream().anyMatch(entry ->
                    !entry.currency().equals(transaction.sourceCurrency()))) {
                discrepancy(
                        discrepancies,
                        transaction,
                        "SAME_CURRENCY_ENTRY_MISMATCH",
                        "Ledger entries do not match the transfer's source and destination accounts, currency, and amounts."
                );
            }
            return;
        }

        BigDecimal rate = transaction.exchangeRate();
        if (rate == null) {
            discrepancy(
                    discrepancies,
                    transaction,
                    "FX_RATE_MISSING",
                    "Cross-currency transfer has no persisted exchange rate."
            );
        } else if (rate.compareTo(BigDecimal.ZERO) <= 0) {
            discrepancy(
                    discrepancies,
                    transaction,
                    "FX_RATE_INVALID",
                    "Persisted exchange rate must be greater than zero."
            );
        } else {
            BigDecimal expectedDestination = transaction.sourceAmount()
                    .multiply(rate)
                    .setScale(4, RoundingMode.HALF_EVEN);
            if (expectedDestination.compareTo(transaction.destinationAmount()) != 0) {
                discrepancy(
                        discrepancies,
                        transaction,
                        "FX_DESTINATION_AMOUNT_MISMATCH",
                        "Expected %s %s but transaction stores %s %s."
                                .formatted(expectedDestination.toPlainString(),
                                        transaction.destinationCurrency(),
                                        transaction.destinationAmount().toPlainString(),
                                        transaction.destinationCurrency())
                );
            }
        }

        if (sourceEntry.compareTo(transaction.sourceAmount().negate()) != 0
                || entries.stream().filter(entry -> entry.accountId()
                        .equals(transaction.sourceAccountId()))
                .anyMatch(entry -> !entry.currency().equals(transaction.sourceCurrency()))) {
            discrepancy(
                    discrepancies,
                    transaction,
                    "FX_SOURCE_ENTRY_MISMATCH",
                    "Source ledger entry does not match the source account, currency, and debit amount."
            );
        }
        if (destinationEntry.compareTo(transaction.destinationAmount()) != 0
                || entries.stream().filter(entry -> entry.accountId()
                        .equals(transaction.destinationAccountId()))
                .anyMatch(entry -> !entry.currency().equals(transaction.destinationCurrency()))) {
            discrepancy(
                    discrepancies,
                    transaction,
                    "FX_DESTINATION_ENTRY_MISMATCH",
                    "Destination ledger entry does not match the destination account, currency, and credit amount."
            );
        }
    }

    private void checkReversal(
            TransactionAudit reversal,
            List<LedgerAuditEntry> reversalEntries,
            Map<UUID, TransactionAudit> originals,
            Map<UUID, List<LedgerAuditEntry>> entriesByTransaction,
            List<ReconciliationDiscrepancy> discrepancies
    ) {
        TransactionAudit original = reversal.reversalOf() == null
                ? null
                : originals.get(reversal.reversalOf());
        if (original == null) {
            discrepancy(
                    discrepancies,
                    reversal,
                    "REVERSAL_ORIGINAL_MISSING",
                    "Reversal does not reference an existing original transaction."
            );
            return;
        }
        if (original.type() != TransactionType.TRANSFER) {
            discrepancy(
                    discrepancies,
                    reversal,
                    "REVERSAL_ORIGINAL_INVALID",
                    "A reversal must reference an original TRANSFER."
            );
        }
        if (!metadataMatches(reversal, original)) {
            discrepancy(
                    discrepancies,
                    reversal,
                    "REVERSAL_METADATA_MISMATCH",
                    "Reversal metadata does not match the original transaction."
            );
        }

        List<LedgerAuditEntry> originalEntries = entriesByTransaction.getOrDefault(
                original.transactionId(),
                List.of()
        );
        if (!areInverseEntries(originalEntries, reversalEntries)) {
            discrepancy(
                    discrepancies,
                    reversal,
                    "REVERSAL_ENTRY_MISMATCH",
                    "Reversal ledger entries are not exact sign inverses of the original entries."
            );
        }
    }

    private boolean metadataMatches(TransactionAudit reversal, TransactionAudit original) {
        return reversal.sourceAccountId().equals(original.sourceAccountId())
                && reversal.destinationAccountId().equals(original.destinationAccountId())
                && reversal.sourceAmount().compareTo(original.sourceAmount()) == 0
                && reversal.sourceCurrency().equals(original.sourceCurrency())
                && reversal.destinationAmount().compareTo(original.destinationAmount()) == 0
                && reversal.destinationCurrency().equals(original.destinationCurrency())
                && decimalEquals(reversal.exchangeRate(), original.exchangeRate());
    }

    private boolean areInverseEntries(
            List<LedgerAuditEntry> originalEntries,
            List<LedgerAuditEntry> reversalEntries
    ) {
        if (originalEntries.size() != reversalEntries.size()) {
            return false;
        }
        Map<EntryKey, BigDecimal> originalByAccount = entryTotals(originalEntries);
        Map<EntryKey, BigDecimal> reversalByAccount = entryTotals(reversalEntries);
        if (!originalByAccount.keySet().equals(reversalByAccount.keySet())) {
            return false;
        }
        return originalByAccount.entrySet().stream().allMatch(entry ->
                entry.getValue().negate().compareTo(reversalByAccount.get(entry.getKey())) == 0
        );
    }

    private Map<EntryKey, BigDecimal> entryTotals(List<LedgerAuditEntry> entries) {
        Map<EntryKey, BigDecimal> totals = new HashMap<>();
        for (LedgerAuditEntry entry : entries) {
            totals.merge(new EntryKey(entry.accountId(), entry.currency()), entry.amount(),
                    BigDecimal::add);
        }
        return totals;
    }

    private BigDecimal amountFor(List<LedgerAuditEntry> entries, UUID accountId) {
        return entries.stream()
                .filter(entry -> entry.accountId().equals(accountId))
                .map(LedgerAuditEntry::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private boolean decimalEquals(BigDecimal first, BigDecimal second) {
        return first == null ? second == null : second != null && first.compareTo(second) == 0;
    }

    private BigDecimal scale(BigDecimal amount) {
        return amount.setScale(4, RoundingMode.UNNECESSARY);
    }

    private void discrepancy(
            List<ReconciliationDiscrepancy> discrepancies,
            TransactionAudit transaction,
            String code,
            String detail
    ) {
        discrepancies.add(ReconciliationDiscrepancy.forTransaction(
                transaction.transactionId(),
                code,
                detail
        ));
    }

    private record EntryKey(UUID accountId, String currency) {
    }

    private static final class CurrencyTotalsBuilder {
        private BigDecimal openingBalance = ZERO;
        private BigDecimal ledgerEffect = ZERO;
        private BigDecimal expectedBalance = ZERO;
        private BigDecimal storedBalance = ZERO;
        private boolean hasDiscrepancy;
    }
}
