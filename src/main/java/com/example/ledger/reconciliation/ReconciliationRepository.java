package com.example.ledger.reconciliation;

import com.example.ledger.transaction.TransactionType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Repository
public class ReconciliationRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ReconciliationRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<AccountLedgerSnapshot> findCurrentAccountSnapshots() {
        return findAccountSnapshots(null);
    }

    public List<AccountLedgerSnapshot> findHistoricalAccountSnapshots(Instant cutoff) {
        return findAccountSnapshots(cutoff);
    }

    private List<AccountLedgerSnapshot> findAccountSnapshots(Instant cutoff) {
        String entryCutoff = cutoff == null ? "" : "AND entry.created_at < :cutoff ";
        String sql = """
                SELECT account.id AS account_id,
                       account.currency,
                       account.opening_balance,
                       account.balance AS stored_balance,
                       COALESCE(SUM(entry.amount), 0) AS ledger_effect
                FROM accounts account
                LEFT JOIN ledger_entries entry
                  ON entry.account_id = account.id
                """ + (cutoff == null ? "" : entryCutoff) + """
                GROUP BY account.id, account.currency, account.opening_balance, account.balance
                ORDER BY account.id
                """;
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        if (cutoff != null) {
            parameters.addValue("cutoff", cutoff.atOffset(ZoneOffset.UTC));
        }
        return jdbcTemplate.query(sql, parameters, (row, rowNumber) -> new AccountLedgerSnapshot(
                row.getObject("account_id", UUID.class),
                row.getString("currency"),
                row.getBigDecimal("opening_balance"),
                row.getBigDecimal("stored_balance"),
                row.getBigDecimal("ledger_effect")
        ));
    }

    public List<TransactionAudit> findTransactions(Instant cutoff) {
        String sql = """
                SELECT id,
                       source_account_id,
                       destination_account_id,
                       source_amount,
                       source_currency,
                       destination_amount,
                       destination_currency,
                       exchange_rate,
                       transaction_type,
                       reversal_of
                FROM ledger_transactions
                """ + (cutoff == null ? "" : "WHERE created_at < :cutoff ") + """
                ORDER BY created_at, id
                """;
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        if (cutoff != null) {
            parameters.addValue("cutoff", cutoff.atOffset(ZoneOffset.UTC));
        }
        return jdbcTemplate.query(sql, parameters, (row, rowNumber) -> new TransactionAudit(
                row.getObject("id", UUID.class),
                row.getObject("source_account_id", UUID.class),
                row.getObject("destination_account_id", UUID.class),
                row.getBigDecimal("source_amount"),
                row.getString("source_currency"),
                row.getBigDecimal("destination_amount"),
                row.getString("destination_currency"),
                row.getBigDecimal("exchange_rate"),
                TransactionType.valueOf(row.getString("transaction_type")),
                row.getObject("reversal_of", UUID.class)
        ));
    }

    public List<LedgerAuditEntry> findEntries(List<UUID> transactionIds, Instant cutoff) {
        if (transactionIds.isEmpty()) {
            return List.of();
        }
        String timeFilter = cutoff == null ? "" : "AND entry.created_at < :cutoff ";
        String sql = """
                SELECT entry.transaction_id,
                       entry.account_id,
                       account.currency,
                       entry.amount
                FROM ledger_entries entry
                JOIN accounts account ON account.id = entry.account_id
                WHERE entry.transaction_id IN (:transactionIds)
                """ + timeFilter + """
                ORDER BY entry.transaction_id, entry.account_id, entry.id
                """;
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("transactionIds", transactionIds);
        if (cutoff != null) {
            parameters.addValue("cutoff", cutoff.atOffset(ZoneOffset.UTC));
        }
        return jdbcTemplate.query(sql, parameters, (row, rowNumber) -> new LedgerAuditEntry(
                row.getObject("transaction_id", UUID.class),
                row.getObject("account_id", UUID.class),
                row.getString("currency"),
                row.getBigDecimal("amount")
        ));
    }

    public List<TransactionAudit> findTransactionsByIds(List<UUID> transactionIds) {
        if (transactionIds.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query("""
                        SELECT id,
                               source_account_id,
                               destination_account_id,
                               source_amount,
                               source_currency,
                               destination_amount,
                               destination_currency,
                               exchange_rate,
                               transaction_type,
                               reversal_of
                        FROM ledger_transactions
                        WHERE id IN (:transactionIds)
                        """,
                new MapSqlParameterSource("transactionIds", transactionIds),
                (row, rowNumber) -> new TransactionAudit(
                        row.getObject("id", UUID.class),
                        row.getObject("source_account_id", UUID.class),
                        row.getObject("destination_account_id", UUID.class),
                        row.getBigDecimal("source_amount"),
                        row.getString("source_currency"),
                        row.getBigDecimal("destination_amount"),
                        row.getString("destination_currency"),
                        row.getBigDecimal("exchange_rate"),
                        TransactionType.valueOf(row.getString("transaction_type")),
                        row.getObject("reversal_of", UUID.class)
                ));
    }
}
