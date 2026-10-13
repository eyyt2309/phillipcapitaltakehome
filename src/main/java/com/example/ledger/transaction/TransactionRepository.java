package com.example.ledger.transaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<LedgerTransaction, UUID> {

    Optional<LedgerTransaction> findByIdempotencyKey(String idempotencyKey);

    @Query("""
            SELECT transaction
            FROM LedgerTransaction transaction
            JOIN FETCH transaction.sourceAccount
            JOIN FETCH transaction.destinationAccount
            WHERE transaction.id = :transactionId
            """)
    Optional<LedgerTransaction> findWithAccountsById(@Param("transactionId") UUID transactionId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO ledger_transactions (
                id,
                source_account_id,
                destination_account_id,
                source_amount,
                source_currency,
                destination_amount,
                destination_currency,
                exchange_rate,
                transaction_type,
                idempotency_key,
                request_hash,
                reversal_of,
                created_at
            )
            VALUES (
                :transactionId,
                :sourceAccountId,
                :destinationAccountId,
                :amount,
                :sourceCurrency,
                :amount,
                :destinationCurrency,
                NULL,
                'TRANSFER',
                :idempotencyKey,
                CAST(:requestHash AS CHAR(64)),
                NULL,
                now()
            )
            ON CONFLICT (idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int claimTransfer(
            @Param("transactionId") UUID transactionId,
            @Param("sourceAccountId") UUID sourceAccountId,
            @Param("destinationAccountId") UUID destinationAccountId,
            @Param("amount") BigDecimal amount,
            @Param("sourceCurrency") String sourceCurrency,
            @Param("destinationCurrency") String destinationCurrency,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestHash") String requestHash
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO ledger_transactions (
                id,
                source_account_id,
                destination_account_id,
                source_amount,
                source_currency,
                destination_amount,
                destination_currency,
                exchange_rate,
                transaction_type,
                idempotency_key,
                request_hash,
                reversal_of,
                created_at
            )
            SELECT
                :transactionId,
                original.source_account_id,
                original.destination_account_id,
                original.source_amount,
                original.source_currency,
                original.destination_amount,
                original.destination_currency,
                original.exchange_rate,
                'REVERSAL',
                :idempotencyKey,
                CAST(:requestHash AS CHAR(64)),
                original.id,
                now()
            FROM ledger_transactions original
            WHERE original.id = :originalTransactionId
              AND original.transaction_type = 'TRANSFER'
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int claimReversal(
            @Param("transactionId") UUID transactionId,
            @Param("originalTransactionId") UUID originalTransactionId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestHash") String requestHash
    );
}
