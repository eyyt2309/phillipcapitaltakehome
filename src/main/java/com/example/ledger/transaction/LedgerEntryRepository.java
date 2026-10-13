package com.example.ledger.transaction;

import com.example.ledger.account.AccountTransactionItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    @Query("""
            SELECT new com.example.ledger.account.AccountTransactionItem(
                transaction.id,
                transaction.transactionType,
                entry.amount,
                account.currency,
                transaction.createdAt,
                transaction.reversalOf
            )
            FROM LedgerEntry entry
            JOIN entry.transaction transaction
            JOIN entry.account account
            WHERE account.id = :accountId
            ORDER BY transaction.createdAt ASC, transaction.id ASC
            """)
    List<AccountTransactionItem> findHistoryByAccountId(@Param("accountId") UUID accountId);
}
