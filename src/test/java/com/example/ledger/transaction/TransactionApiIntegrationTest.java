package com.example.ledger.transaction;

import com.example.ledger.account.Account;
import com.example.ledger.account.AccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.NonNull;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TransactionApiIntegrationTest {

    private static final String IDEMPOTENCY_KEY = "test-transfer-key";
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final String BLOCKED_CLAIM_KEY = "blocked-claim-key";
    private static final UUID ACCOUNT_A = Objects.requireNonNull(
            UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final UUID ACCOUNT_B = Objects.requireNonNull(
            UUID.fromString("00000000-0000-0000-0000-000000000002"));
    private static final UUID ACCOUNT_C = Objects.requireNonNull(
            UUID.fromString("00000000-0000-0000-0000-000000000003"));
    private static final UUID HISTORY_OTHER_ACCOUNT = Objects.requireNonNull(
            UUID.fromString("00000000-0000-0000-0000-000000000200"));
    private static final List<UUID> CREDIT_SOURCE_IDS = temporaryAccountIds();

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private RequestHasher requestHasher;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM ledger_entries");
        jdbcTemplate.update("DELETE FROM ledger_transactions");
        for (UUID accountId : CREDIT_SOURCE_IDS) {
            jdbcTemplate.update("DELETE FROM accounts WHERE id = ?", accountId);
        }
        jdbcTemplate.update("DELETE FROM accounts WHERE id = ?", HISTORY_OTHER_ACCOUNT);
        jdbcTemplate.update("UPDATE accounts SET balance = opening_balance");
    }

    @Test
    void postsSameCurrencyTransferAndCreatesBalancedLedgerEntries() throws Exception {
        mockMvc.perform(post("/transactions")
                        .contentType("application/json")
                        .header(IDEMPOTENCY_HEADER, IDEMPOTENCY_KEY)
                        .content(transferJson(ACCOUNT_A, ACCOUNT_B, "100.0000")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.sourceAccountId").value(ACCOUNT_A.toString()))
                .andExpect(jsonPath("$.destinationAccountId").value(ACCOUNT_B.toString()))
                .andExpect(jsonPath("$.sourceAmount").value("100.0000"))
                .andExpect(jsonPath("$.destinationAmount").value("100.0000"))
                .andExpect(jsonPath("$.sourceCurrency").value("SGD"))
                .andExpect(jsonPath("$.destinationCurrency").value("SGD"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.idempotencyKey").doesNotExist())
                .andExpect(jsonPath("$.requestHash").doesNotExist());

        assertBalance(ACCOUNT_A, "900.0000");
        assertBalance(ACCOUNT_B, "600.0000");
        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT idempotency_key FROM ledger_transactions",
                String.class
        )).isEqualTo(IDEMPOTENCY_KEY);
        String storedHash = jdbcTemplate.queryForObject(
                "SELECT request_hash FROM ledger_transactions",
                String.class
        );
        assertThat(storedHash).matches("[0-9a-f]{64}");
        assertThat(storedHash).isEqualTo(requestHasher.hash(
                new TransferRequest(ACCOUNT_A, ACCOUNT_B, new BigDecimal("100.0000"))
        ));

        BigDecimal sourceAmount = jdbcTemplate.queryForObject(
                "SELECT source_amount FROM ledger_transactions",
                BigDecimal.class
        );
        BigDecimal destinationAmount = jdbcTemplate.queryForObject(
                "SELECT destination_amount FROM ledger_transactions",
                BigDecimal.class
        );
        assertThat(sourceAmount).isEqualByComparingTo("100.0000");
        assertThat(destinationAmount).isEqualByComparingTo("100.0000");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT source_account_id = ? AND destination_account_id = ? "
                        + "AND transaction_type = 'TRANSFER' AND exchange_rate IS NULL "
                        + "AND reversal_of IS NULL FROM ledger_transactions",
                Boolean.class,
                ACCOUNT_A,
                ACCOUNT_B
        )).isTrue();

        BigDecimal sourceEntry = entryAmount(ACCOUNT_A);
        BigDecimal destinationEntry = entryAmount(ACCOUNT_B);
        assertThat(sourceEntry).isEqualByComparingTo("-100.0000");
        assertThat(destinationEntry).isEqualByComparingTo("100.0000");
        assertThat(sourceEntry.add(destinationEntry)).isEqualByComparingTo(BigDecimal.ZERO);
        assertBalanceMatchesOpeningPlusEntries(ACCOUNT_A);
        assertBalanceMatchesOpeningPlusEntries(ACCOUNT_B);
    }

    @Test
    void seededAccountWithNoTransfersHasEmptyHistory() throws Exception {
        mockMvc.perform(get("/accounts/{id}/transactions", ACCOUNT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void unknownAccountHistoryReturnsNotFound() throws Exception {
        UUID unknownId = Objects.requireNonNull(
                UUID.fromString("00000000-0000-0000-0000-000000000099"));

        mockMvc.perform(get("/accounts/{id}/transactions", unknownId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void sourceAndDestinationHistoryUseSignedLedgerEntryAmounts() throws Exception {
        TransactionResponse posted = postTransfer(
                ACCOUNT_A, ACCOUNT_B, "100.0000", "history-source-destination"
        );

        mockMvc.perform(get("/accounts/{id}/transactions", ACCOUNT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].transactionId")
                        .value(posted.transactionId().toString()))
                .andExpect(jsonPath("$.items[0].type").value("TRANSFER"))
                .andExpect(jsonPath("$.items[0].amount").value("-100.0000"))
                .andExpect(jsonPath("$.items[0].currency").value("SGD"))
                .andExpect(jsonPath("$.items[0].reversalOf").doesNotExist());

        mockMvc.perform(get("/accounts/{id}/transactions", ACCOUNT_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].transactionId")
                        .value(posted.transactionId().toString()))
                .andExpect(jsonPath("$.items[0].amount").value("100.0000"))
                .andExpect(jsonPath("$.items[0].currency").value("SGD"))
                .andExpect(jsonPath("$.items[0].reversalOf").doesNotExist());
    }

    @Test
    void multipleAndIrrelevantTransfersAreOrderedByCreationTimeAndTransactionId()
            throws Exception {
        TransactionResponse first = postTransfer(
                ACCOUNT_A, ACCOUNT_B, "100.0000", "history-order-first"
        );
        TransactionResponse second = postTransfer(
                ACCOUNT_A, ACCOUNT_B, "20.0000", "history-order-second"
        );
        jdbcTemplate.update(
                """
                INSERT INTO accounts (id, currency, opening_balance, balance)
                VALUES (?, 'SGD', 100.0000, 100.0000)
                """,
                HISTORY_OTHER_ACCOUNT
        );
        TransactionResponse irrelevant = postTransfer(
                ACCOUNT_B, HISTORY_OTHER_ACCOUNT, "30.0000", "history-order-irrelevant"
        );

        Timestamp sameCreationTime =
                Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
        jdbcTemplate.update(
                "UPDATE ledger_transactions SET created_at = ? WHERE id IN (?, ?)",
                sameCreationTime,
                first.transactionId(),
                second.transactionId()
        );
        List<UUID> expectedOrder = jdbcTemplate.query(
                """
                SELECT id
                FROM ledger_transactions
                WHERE id IN (?, ?)
                ORDER BY created_at ASC, id ASC
                """,
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                first.transactionId(),
                second.transactionId()
        );

        mockMvc.perform(get("/accounts/{id}/transactions", ACCOUNT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].transactionId")
                        .value(expectedOrder.get(0).toString()))
                .andExpect(jsonPath("$.items[1].transactionId")
                        .value(expectedOrder.get(1).toString()));

        assertThat(expectedOrder).containsExactlyInAnyOrder(
                first.transactionId(),
                second.transactionId()
        );
        assertThat(expectedOrder).doesNotContain(irrelevant.transactionId());
        for (int i = 0; i < expectedOrder.size(); i++) {
            UUID transactionId = expectedOrder.get(i);
            BigDecimal expectedAmount = transactionId.equals(first.transactionId())
                    ? new BigDecimal("-100.0000")
                    : new BigDecimal("-20.0000");
            mockMvc.perform(get("/accounts/{id}/transactions", ACCOUNT_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[" + i + "].amount")
                            .value(expectedAmount.toPlainString()));
        }
    }

    @Test
    void idempotentReplayAppearsOnlyOnceInAccountHistory() throws Exception {
        TransactionResponse first = postTransfer(
                ACCOUNT_A, ACCOUNT_B, "100.0000", "history-idempotent-replay"
        );
        MvcResult replay = mockMvc.perform(post("/transactions")
                        .contentType("application/json")
                        .header(IDEMPOTENCY_HEADER, "history-idempotent-replay")
                        .content(transferJson(ACCOUNT_A, ACCOUNT_B, "100")))
                .andExpect(status().isOk())
                .andReturn();
        TransactionResponse replayed = objectMapper.readValue(
                replay.getResponse().getContentAsString(),
                TransactionResponse.class
        );

        assertThat(replayed.transactionId()).isEqualTo(first.transactionId());
        mockMvc.perform(get("/accounts/{id}/transactions", ACCOUNT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].transactionId")
                        .value(first.transactionId().toString()))
                .andExpect(jsonPath("$.items[0].amount").value("-100.0000"));
    }

    @Test
    void sequentialReplayReturnsOriginalTransactionWithoutPostingAgain() throws Exception {
        TransferRequest firstRequest =
                new TransferRequest(ACCOUNT_A, ACCOUNT_B, new BigDecimal("100.0000"));
        MvcResult first = mockMvc.perform(post("/transactions")
                        .contentType("application/json")
                        .header(IDEMPOTENCY_HEADER, IDEMPOTENCY_KEY)
                        .content(transferJson(ACCOUNT_A, ACCOUNT_B, "100.0000")))
                .andExpect(status().isCreated())
                .andReturn();
        TransactionResponse original = objectMapper.readValue(
                first.getResponse().getContentAsString(),
                TransactionResponse.class
        );

        MvcResult replay = mockMvc.perform(post("/transactions")
                        .contentType("application/json")
                        .header(IDEMPOTENCY_HEADER, IDEMPOTENCY_KEY)
                        .content(transferJson(ACCOUNT_A, ACCOUNT_B, "100")))
                .andExpect(status().isOk())
                .andReturn();
        TransactionResponse replayed = objectMapper.readValue(
                replay.getResponse().getContentAsString(),
                TransactionResponse.class
        );

        assertThat(replayed).isEqualTo(original);
        assertThat(requestHasher.hash(firstRequest))
                .isEqualTo(requestHasher.hash(
                        new TransferRequest(ACCOUNT_A, ACCOUNT_B, new BigDecimal("100"))
                ));
        assertBalance(ACCOUNT_A, "900.0000");
        assertBalance(ACCOUNT_B, "600.0000");
        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
    }

    @Test
    void sameKeyWithDifferentAmountReturnsConflictWithoutPostingAgain() throws Exception {
        mockMvc.perform(post("/transactions")
                        .contentType("application/json")
                        .header(IDEMPOTENCY_HEADER, IDEMPOTENCY_KEY)
                        .content(transferJson(ACCOUNT_A, ACCOUNT_B, "100.0000")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/transactions")
                        .contentType("application/json")
                        .header(IDEMPOTENCY_HEADER, IDEMPOTENCY_KEY)
                        .content(transferJson(ACCOUNT_A, ACCOUNT_B, "200.0000")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertBalance(ACCOUNT_A, "900.0000");
        assertBalance(ACCOUNT_B, "600.0000");
        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
    }

    @Test
    void sameKeyWithDifferentDestinationReturnsConflictWithoutPostingAgain() throws Exception {
        mockMvc.perform(post("/transactions")
                        .contentType("application/json")
                        .header(IDEMPOTENCY_HEADER, IDEMPOTENCY_KEY)
                        .content(transferJson(ACCOUNT_A, ACCOUNT_B, "100.0000")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/transactions")
                        .contentType("application/json")
                        .header(IDEMPOTENCY_HEADER, IDEMPOTENCY_KEY)
                        .content(transferJson(ACCOUNT_A, ACCOUNT_C, "100.0000")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertBalance(ACCOUNT_A, "900.0000");
        assertBalance(ACCOUNT_B, "600.0000");
        assertBalance(ACCOUNT_C, "1000.0000");
        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
    }

    @Test
    void missingBlankAndOversizedIdempotencyKeysAreRejected() throws Exception {
        String body = transferJson(ACCOUNT_A, ACCOUNT_B, "100.0000");
        mockMvc.perform(post("/transactions")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/transactions")
                        .contentType("application/json")
                        .header(IDEMPOTENCY_HEADER, "   ")
                        .content(body))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/transactions")
                        .contentType("application/json")
                        .header(IDEMPOTENCY_HEADER, "x".repeat(256))
                        .content(body))
                .andExpect(status().isBadRequest());

        assertThat(transactionRepository.count()).isZero();
        assertThat(ledgerEntryRepository.count()).isZero();
    }

    @Test
    void insufficientFundsLeavesBalancesAndLedgerUnchanged() throws Exception {
        jdbcTemplate.update(
                "UPDATE accounts SET balance = 50.0000 WHERE id = ?",
                ACCOUNT_A
        );

        mockMvc.perform(post("/transactions")
                        .contentType("application/json")
                        .header(IDEMPOTENCY_HEADER, IDEMPOTENCY_KEY)
                        .content(transferJson(ACCOUNT_A, ACCOUNT_B, "100.0000")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        assertBalance(ACCOUNT_A, "50.0000");
        assertBalance(ACCOUNT_B, "500.0000");
        assertThat(transactionRepository.count()).isZero();
        assertThat(ledgerEntryRepository.count()).isZero();
    }

    @Test
    void selfTransferIsRejectedWithoutChanges() throws Exception {
        mockMvc.perform(post("/transactions")
                        .contentType("application/json")
                        .header(IDEMPOTENCY_HEADER, IDEMPOTENCY_KEY)
                        .content(transferJson(ACCOUNT_A, ACCOUNT_A, "100.0000")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SELF_TRANSFER"));

        assertBalance(ACCOUNT_A, "1000.0000");
        assertThat(transactionRepository.count()).isZero();
        assertThat(ledgerEntryRepository.count()).isZero();
    }

    @Test
    void currencyMismatchIsRejectedWithoutChanges() throws Exception {
        mockMvc.perform(post("/transactions")
                        .contentType("application/json")
                        .header(IDEMPOTENCY_HEADER, IDEMPOTENCY_KEY)
                        .content(transferJson(ACCOUNT_A, ACCOUNT_C, "100.0000")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CURRENCY_MISMATCH"));

        assertBalance(ACCOUNT_A, "1000.0000");
        assertBalance(ACCOUNT_C, "1000.0000");
        assertThat(transactionRepository.count()).isZero();
        assertThat(ledgerEntryRepository.count()).isZero();
    }

    @Test
    void accountNotFoundIsReturnedWithoutPersistingTransaction() throws Exception {
        UUID unknownAccountId = Objects.requireNonNull(
                UUID.fromString("00000000-0000-0000-0000-000000000099"));

        mockMvc.perform(post("/transactions")
                        .contentType("application/json")
                        .header(IDEMPOTENCY_HEADER, IDEMPOTENCY_KEY)
                        .content(transferJson(ACCOUNT_A, unknownAccountId, "1.0000")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertBalance(ACCOUNT_A, "1000.0000");
        assertThat(transactionRepository.count()).isZero();
        assertThat(ledgerEntryRepository.count()).isZero();
    }

    @Test
    void invalidAmountIsRejectedByBeanValidation() throws Exception {
        mockMvc.perform(post("/transactions")
                        .contentType("application/json")
                        .header(IDEMPOTENCY_HEADER, IDEMPOTENCY_KEY)
                        .content(transferJson(ACCOUNT_A, ACCOUNT_B, "0.0000")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/transactions")
                        .contentType("application/json")
                        .header(IDEMPOTENCY_HEADER, IDEMPOTENCY_KEY)
                        .content(transferJson(ACCOUNT_A, ACCOUNT_B, "1.00001")))
                .andExpect(status().isBadRequest());

        assertThat(transactionRepository.count()).isZero();
        assertThat(ledgerEntryRepository.count()).isZero();
    }

    @Test
    void failureInLedgerEntryInsertRollsBackBalanceAndTransactionChanges() throws Exception {
        installFailingLedgerEntryTrigger();
        try {
            Throwable failure = catchThrowable(() -> transactionService.transfer(
                    new TransferRequest(ACCOUNT_A, ACCOUNT_B, new BigDecimal("100.0000")),
                    IDEMPOTENCY_KEY
            ));

            assertThat(failure).isNotNull();
            assertBalance(ACCOUNT_A, "1000.0000");
            assertBalance(ACCOUNT_B, "500.0000");
            assertThat(transactionRepository.count()).isZero();
            assertThat(ledgerEntryRepository.count()).isZero();
        } finally {
            removeFailingLedgerEntryTrigger();
        }
    }

    @Test
    void concurrentSpendingAllowsOnlyTransfersCoveredBySourceBalance() throws Exception {
        List<TransferRequest> requests = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            requests.add(new TransferRequest(ACCOUNT_A, ACCOUNT_B, new BigDecimal("100.0000")));
            keys.add("spending-" + i);
        }

        List<ConcurrentAttempt> results = runConcurrently(requests, keys);

        assertThat(results.stream().filter(result -> result.result() != null).count()).isEqualTo(10);
        assertThat(results.stream()
                .filter(result -> result.failure() instanceof InsufficientFundsException)
                .count()).isEqualTo(10);
        assertBalance(ACCOUNT_A, "0.0000");
        assertThat(accountRepository.findById(Objects.requireNonNull(ACCOUNT_A)).orElseThrow().getBalance())
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(transactionRepository.count()).isEqualTo(10);
        assertThat(ledgerEntryRepository.count()).isEqualTo(20);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT SUM(amount) FROM ledger_entries WHERE account_id = ?",
                BigDecimal.class,
                ACCOUNT_A
        )).isEqualByComparingTo("-1000.0000");
        assertBalanceMatchesOpeningPlusEntries(ACCOUNT_A);
        assertBalanceMatchesOpeningPlusEntries(ACCOUNT_B);
    }

    @Test
    void concurrentCreditsToOneDestinationAreNotLost() throws Exception {
        List<TransferRequest> requests = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        int index = 0;
        for (UUID sourceId : CREDIT_SOURCE_IDS) {
            jdbcTemplate.update(
                    """
                    INSERT INTO accounts (id, currency, opening_balance, balance)
                    VALUES (?, 'SGD', 100.0000, 100.0000)
                    """,
                    sourceId
            );
            requests.add(new TransferRequest(sourceId, ACCOUNT_B, new BigDecimal("10.0000")));
            keys.add("credit-" + index++);
        }

        List<ConcurrentAttempt> results = runConcurrently(requests, keys);

        assertThat(results).allSatisfy(result -> assertThat(result.failure()).isNull());
        assertThat(results).allSatisfy(result -> assertThat(result.result()).isNotNull());
        assertBalance(ACCOUNT_B, "600.0000");
        assertThat(transactionRepository.count()).isEqualTo(CREDIT_SOURCE_IDS.size());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT SUM(amount) FROM ledger_entries WHERE account_id = ?",
                BigDecimal.class,
                ACCOUNT_B
        )).isEqualByComparingTo("100.0000");
        assertBalanceMatchesOpeningPlusEntries(ACCOUNT_B);
    }

    @Test
    void concurrentDuplicateRequestsPostOnlyOnceAndShareTheOriginalResponse() throws Exception {
        List<TransferRequest> requests = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            requests.add(new TransferRequest(ACCOUNT_A, ACCOUNT_B, new BigDecimal("100.0000")));
            keys.add(IDEMPOTENCY_KEY);
        }

        List<ConcurrentAttempt> attempts = runConcurrently(requests, keys);

        assertThat(attempts).allSatisfy(attempt -> assertThat(attempt.failure()).isNull());
        assertThat(attempts).allSatisfy(attempt -> assertThat(attempt.result()).isNotNull());
        assertThat(attempts.stream().filter(attempt -> !attempt.result().replayed()).count())
                .isEqualTo(1);
        assertThat(attempts.stream().filter(attempt -> attempt.result().replayed()).count())
                .isEqualTo(11);
        assertThat(attempts.stream()
                .map(attempt -> attempt.result().response().transactionId())
                .distinct()).hasSize(1);
        assertBalance(ACCOUNT_A, "900.0000");
        assertBalance(ACCOUNT_B, "600.0000");
        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
    }

    @Test
    void duplicateLosingToUncommittedClaimReadsWinnerAfterCommit() throws Exception {
        installBlockingIdempotencyClaimTrigger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection lockConnection = dataSource.getConnection();
             Statement statement = lockConnection.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(8675309::bigint)");
            boolean advisoryLockHeld = true;
            try {
                Future<TransferResult> winner = executor.submit(() -> transactionService.transfer(
                        new TransferRequest(ACCOUNT_A, ACCOUNT_B, new BigDecimal("100.0000")),
                        BLOCKED_CLAIM_KEY
                ));
                awaitBlockedClaims(1);

                Future<TransferResult> loser = executor.submit(() -> transactionService.transfer(
                        new TransferRequest(ACCOUNT_A, ACCOUNT_B, new BigDecimal("100.0000")),
                        BLOCKED_CLAIM_KEY
                ));
                awaitBlockedClaims(2);
                statement.execute("SELECT pg_advisory_unlock(8675309::bigint)");
                advisoryLockHeld = false;

                TransferResult winnerResult = winner.get(30, TimeUnit.SECONDS);
                TransferResult loserResult = loser.get(30, TimeUnit.SECONDS);

                assertThat(winnerResult.replayed()).isFalse();
                assertThat(loserResult.replayed()).isTrue();
                assertThat(loserResult.response().transactionId())
                        .isEqualTo(winnerResult.response().transactionId());
                assertBalance(ACCOUNT_A, "900.0000");
                assertBalance(ACCOUNT_B, "600.0000");
                assertThat(transactionRepository.count()).isEqualTo(1);
                assertThat(ledgerEntryRepository.count()).isEqualTo(2);
            } finally {
                if (advisoryLockHeld) {
                    statement.execute("SELECT pg_advisory_unlock(8675309::bigint)");
                }
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            removeBlockingIdempotencyClaimTrigger();
        }
    }

    @Test
    void concurrentDifferentRequestsWithSameKeyHaveOneWinnerAndOneConflict() throws Exception {
        List<TransferRequest> requests = List.of(
                new TransferRequest(ACCOUNT_A, ACCOUNT_B, new BigDecimal("100.0000")),
                new TransferRequest(ACCOUNT_A, ACCOUNT_B, new BigDecimal("200.0000"))
        );

        List<ConcurrentAttempt> attempts = runConcurrently(
                requests,
                List.of(IDEMPOTENCY_KEY, IDEMPOTENCY_KEY)
        );

        assertThat(attempts.stream().filter(attempt -> attempt.result() != null).count())
                .isEqualTo(1);
        assertThat(attempts.stream()
                .filter(attempt -> attempt.failure() instanceof IdempotencyConflictException)
                .count()).isEqualTo(1);
        assertThat(attempts.stream()
                .filter(attempt -> attempt.failure() != null
                        && !(attempt.failure() instanceof IdempotencyConflictException))
                .count()).isZero();
        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
        BigDecimal winningAmount = jdbcTemplate.queryForObject(
                "SELECT source_amount FROM ledger_transactions",
                BigDecimal.class
        );
        assertThat(winningAmount).isIn(new BigDecimal("100.0000"), new BigDecimal("200.0000"));
        assertBalance(ACCOUNT_A, new BigDecimal("1000.0000").subtract(winningAmount).toPlainString());
        assertBalance(ACCOUNT_B, new BigDecimal("500.0000").add(winningAmount).toPlainString());
        assertBalanceMatchesOpeningPlusEntries(ACCOUNT_A);
        assertBalanceMatchesOpeningPlusEntries(ACCOUNT_B);
    }

    @Test
    void failedClaimRollsBackAndCanBeClaimedOnRetry() {
        TransferRequest request =
                new TransferRequest(ACCOUNT_A, ACCOUNT_B, new BigDecimal("100.0000"));
        installFailingLedgerEntryTrigger();
        try {
            Throwable failure = catchThrowable(
                    () -> transactionService.transfer(request, IDEMPOTENCY_KEY)
            );
            assertThat(failure).isNotNull();
        } finally {
            removeFailingLedgerEntryTrigger();
        }

        TransferResult retry = transactionService.transfer(request, IDEMPOTENCY_KEY);

        assertThat(retry.replayed()).isFalse();
        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
        assertBalance(ACCOUNT_A, "900.0000");
        assertBalance(ACCOUNT_B, "600.0000");
    }

    @Test
    void successfulReversalInvertsEntriesPreservesOriginalAndAppearsInHistory() throws Exception {
        TransactionResponse original = postTransfer(
                ACCOUNT_A, ACCOUNT_B, "100.0000", "reversal-original"
        );
        String originalCreatedAt = jdbcTemplate.queryForObject(
                "SELECT created_at::text FROM ledger_transactions WHERE id = ?",
                String.class,
                original.transactionId()
        );

        MvcResult result = mockMvc.perform(post(
                                "/transactions/{transactionId}/reversals",
                                original.transactionId()
                        )
                        .header(IDEMPOTENCY_HEADER, "reversal-success"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("REVERSAL"))
                .andExpect(jsonPath("$.reversalOf").value(original.transactionId().toString()))
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andReturn();
        TransactionResponse reversal = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                TransactionResponse.class
        );

        assertThat(reversal.sourceAccountId()).isEqualTo(original.sourceAccountId());
        assertThat(reversal.destinationAccountId()).isEqualTo(original.destinationAccountId());
        assertThat(reversal.sourceAmount()).isEqualByComparingTo(original.sourceAmount());
        assertThat(reversal.destinationAmount()).isEqualByComparingTo(original.destinationAmount());
        assertThat(reversal.sourceCurrency()).isEqualTo(original.sourceCurrency());
        assertThat(reversal.destinationCurrency()).isEqualTo(original.destinationCurrency());
        assertBalance(ACCOUNT_A, "1000.0000");
        assertBalance(ACCOUNT_B, "500.0000");
        assertThat(transactionRepository.count()).isEqualTo(2);
        assertThat(ledgerEntryRepository.count()).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT transaction_type = 'TRANSFER'
                   AND reversal_of IS NULL
                   AND created_at::text = ?
                FROM ledger_transactions
                WHERE id = ?
                """,
                Boolean.class,
                originalCreatedAt,
                original.transactionId()
        )).isTrue();
        assertLedgerEffect(original.transactionId(), ACCOUNT_A, "-100.0000");
        assertLedgerEffect(original.transactionId(), ACCOUNT_B, "100.0000");
        assertLedgerEffect(reversal.transactionId(), ACCOUNT_A, "100.0000");
        assertLedgerEffect(reversal.transactionId(), ACCOUNT_B, "-100.0000");
        assertBalanceMatchesOpeningPlusEntries(ACCOUNT_A);
        assertBalanceMatchesOpeningPlusEntries(ACCOUNT_B);

        mockMvc.perform(get("/accounts/{id}/transactions", ACCOUNT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].transactionId")
                        .value(original.transactionId().toString()))
                .andExpect(jsonPath("$.items[0].amount").value("-100.0000"))
                .andExpect(jsonPath("$.items[1].transactionId")
                        .value(reversal.transactionId().toString()))
                .andExpect(jsonPath("$.items[1].type").value("REVERSAL"))
                .andExpect(jsonPath("$.items[1].amount").value("100.0000"))
                .andExpect(jsonPath("$.items[1].reversalOf")
                        .value(original.transactionId().toString()));
        mockMvc.perform(get("/accounts/{id}/transactions", ACCOUNT_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].amount").value("100.0000"))
                .andExpect(jsonPath("$.items[1].amount").value("-100.0000"));
    }

    @Test
    void duplicateReversalWithDifferentKeysIsRejected() throws Exception {
        TransactionResponse original = postTransfer(
                ACCOUNT_A, ACCOUNT_B, "100.0000", "duplicate-reversal-original"
        );
        mockMvc.perform(post("/transactions/{transactionId}/reversals", original.transactionId())
                        .header(IDEMPOTENCY_HEADER, "duplicate-reversal-first"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/transactions/{transactionId}/reversals", original.transactionId())
                        .header(IDEMPOTENCY_HEADER, "duplicate-reversal-second"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REVERSAL"));

        assertThat(transactionRepository.count()).isEqualTo(2);
        assertThat(ledgerEntryRepository.count()).isEqualTo(4);
        assertBalance(ACCOUNT_A, "1000.0000");
        assertBalance(ACCOUNT_B, "500.0000");
    }

    @Test
    void reversalReplayReturnsOriginalReversalWithoutPostingAgain() throws Exception {
        TransactionResponse original = postTransfer(
                ACCOUNT_A, ACCOUNT_B, "100.0000", "replay-reversal-original"
        );
        MvcResult first = mockMvc.perform(post(
                                "/transactions/{transactionId}/reversals",
                                original.transactionId()
                        )
                        .header(IDEMPOTENCY_HEADER, "reversal-replay-key"))
                .andExpect(status().isCreated())
                .andReturn();
        TransactionResponse firstResponse = objectMapper.readValue(
                first.getResponse().getContentAsString(),
                TransactionResponse.class
        );
        MvcResult replay = mockMvc.perform(post(
                                "/transactions/{transactionId}/reversals",
                                original.transactionId()
                        )
                        .header(IDEMPOTENCY_HEADER, "reversal-replay-key"))
                .andExpect(status().isOk())
                .andReturn();
        TransactionResponse replayResponse = objectMapper.readValue(
                replay.getResponse().getContentAsString(),
                TransactionResponse.class
        );

        assertThat(replayResponse).isEqualTo(firstResponse);
        assertThat(transactionRepository.count()).isEqualTo(2);
        assertThat(ledgerEntryRepository.count()).isEqualTo(4);
        assertBalance(ACCOUNT_A, "1000.0000");
        assertBalance(ACCOUNT_B, "500.0000");
    }

    @Test
    void reversalIdempotencyKeyCannotBeReusedForAnotherTarget() throws Exception {
        TransactionResponse first = postTransfer(
                ACCOUNT_A, ACCOUNT_B, "100.0000", "different-target-first"
        );
        TransactionResponse second = postTransfer(
                ACCOUNT_A, ACCOUNT_B, "50.0000", "different-target-second"
        );
        mockMvc.perform(post("/transactions/{transactionId}/reversals", first.transactionId())
                        .header(IDEMPOTENCY_HEADER, "reversal-target-key"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/transactions/{transactionId}/reversals", second.transactionId())
                        .header(IDEMPOTENCY_HEADER, "reversal-target-key"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(transactionRepository.count()).isEqualTo(3);
        assertThat(ledgerEntryRepository.count()).isEqualTo(6);
    }

    @Test
    void reversalOfReversalIsRejected() throws Exception {
        TransactionResponse original = postTransfer(
                ACCOUNT_A, ACCOUNT_B, "100.0000", "reverse-of-reversal-original"
        );
        MvcResult reversalResult = mockMvc.perform(post(
                                "/transactions/{transactionId}/reversals",
                                original.transactionId()
                        )
                        .header(IDEMPOTENCY_HEADER, "reverse-of-reversal-first"))
                .andExpect(status().isCreated())
                .andReturn();
        TransactionResponse reversal = objectMapper.readValue(
                reversalResult.getResponse().getContentAsString(),
                TransactionResponse.class
        );

        mockMvc.perform(post("/transactions/{transactionId}/reversals", reversal.transactionId())
                        .header(IDEMPOTENCY_HEADER, "reverse-the-reversal"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVERSAL_NOT_ALLOWED"));

        assertThat(transactionRepository.count()).isEqualTo(2);
        assertThat(ledgerEntryRepository.count()).isEqualTo(4);
        assertBalance(ACCOUNT_A, "1000.0000");
        assertBalance(ACCOUNT_B, "500.0000");
    }

    @Test
    void insufficientFundsForReversalRollsBackWithoutPartialChanges() throws Exception {
        TransactionResponse original = postTransfer(
                ACCOUNT_A, ACCOUNT_B, "100.0000", "reversal-insufficient-original"
        );
        postTransfer(ACCOUNT_B, ACCOUNT_A, "550.0000", "spend-before-reversal");

        mockMvc.perform(post("/transactions/{transactionId}/reversals", original.transactionId())
                        .header(IDEMPOTENCY_HEADER, "reversal-insufficient-key"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        assertBalance(ACCOUNT_A, "1450.0000");
        assertBalance(ACCOUNT_B, "50.0000");
        assertThat(transactionRepository.count()).isEqualTo(2);
        assertThat(ledgerEntryRepository.count()).isEqualTo(4);
    }

    @Test
    void concurrentAttemptsToReverseOneTransferCommitExactlyOneReversal() throws Exception {
        TransactionResponse original = postTransfer(
                ACCOUNT_A, ACCOUNT_B, "100.0000", "concurrent-reversal-original"
        );
        List<ReversalAttempt> attempts = runConcurrentReversals(
                original.transactionId(),
                List.of("concurrent-reversal-one", "concurrent-reversal-two")
        );

        assertThat(attempts.stream().filter(attempt -> attempt.result() != null).count())
                .isEqualTo(1);
        assertThat(attempts.stream()
                .filter(attempt -> attempt.failure() instanceof DuplicateReversalException)
                .count()).isEqualTo(1);
        assertThat(transactionRepository.count()).isEqualTo(2);
        assertThat(ledgerEntryRepository.count()).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_transactions WHERE transaction_type = 'REVERSAL'",
                Integer.class
        )).isEqualTo(1);
        assertBalance(ACCOUNT_A, "1000.0000");
        assertBalance(ACCOUNT_B, "500.0000");
    }

    @Test
    void failureDuringReversalPostingRollsBackAllReversalState() throws Exception {
        TransactionResponse original = postTransfer(
                ACCOUNT_A, ACCOUNT_B, "100.0000", "reversal-rollback-original"
        );
        installFailingLedgerEntryTrigger();
        try {
            Throwable failure = catchThrowable(() -> transactionService.reverse(
                    original.transactionId(),
                    "reversal-rollback-key"
            ));
            assertThat(failure).isNotNull();
        } finally {
            removeFailingLedgerEntryTrigger();
        }

        assertBalance(ACCOUNT_A, "900.0000");
        assertBalance(ACCOUNT_B, "600.0000");
        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_transactions WHERE transaction_type = 'REVERSAL'",
                Integer.class
        )).isZero();
    }

    @Test
    void failedReversalDoesNotPermanentlyReserveItsIdempotencyKey() throws Exception {
        TransactionResponse original = postTransfer(
                ACCOUNT_A, ACCOUNT_B, "100.0000", "reversal-key-retry-original"
        );
        installFailingLedgerEntryTrigger();
        try {
            Throwable failure = catchThrowable(() -> transactionService.reverse(
                    original.transactionId(),
                    "reversal-key-retry"
            ));
            assertThat(failure).isNotNull();
        } finally {
            removeFailingLedgerEntryTrigger();
        }

        TransferResult retry = transactionService.reverse(
                original.transactionId(),
                "reversal-key-retry"
        );

        assertThat(retry.replayed()).isFalse();
        assertThat(retry.response().type()).isEqualTo(TransactionType.REVERSAL);
        assertThat(transactionRepository.count()).isEqualTo(2);
        assertThat(ledgerEntryRepository.count()).isEqualTo(4);
        assertBalance(ACCOUNT_A, "1000.0000");
        assertBalance(ACCOUNT_B, "500.0000");
    }

    private List<ReversalAttempt> runConcurrentReversals(
            UUID originalTransactionId,
            List<String> idempotencyKeys
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(idempotencyKeys.size());
        CountDownLatch ready = new CountDownLatch(idempotencyKeys.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ReversalAttempt>> futures = new ArrayList<>();
            for (String key : idempotencyKeys) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent reversal start timed out.");
                    }
                    try {
                        return new ReversalAttempt(
                                transactionService.reverse(originalTransactionId, key),
                                null
                        );
                    } catch (RuntimeException exception) {
                        return new ReversalAttempt(null, exception);
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<ReversalAttempt> attempts = new ArrayList<>();
            for (Future<ReversalAttempt> future : futures) {
                attempts.add(future.get(30, TimeUnit.SECONDS));
            }
            return attempts;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private List<ConcurrentAttempt> runConcurrently(
            List<TransferRequest> requests,
            List<String> idempotencyKeys
    ) throws Exception {
        assertThat(idempotencyKeys).hasSize(requests.size());
        ExecutorService executor = Executors.newFixedThreadPool(requests.size());
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ConcurrentAttempt>> futures = new ArrayList<>();
            for (int i = 0; i < requests.size(); i++) {
                TransferRequest request = requests.get(i);
                String idempotencyKey = idempotencyKeys.get(i);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent transfer start timed out.");
                    }

                    try {
                        return new ConcurrentAttempt(
                                transactionService.transfer(request, idempotencyKey),
                                null
                        );
                    } catch (RuntimeException exception) {
                        return new ConcurrentAttempt(null, exception);
                    }
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<ConcurrentAttempt> results = new ArrayList<>();
            for (Future<ConcurrentAttempt> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }

            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private TransactionResponse postTransfer(
            UUID sourceId,
            UUID destinationId,
            String amount,
            String idempotencyKey
    ) throws Exception {
        MvcResult result = mockMvc.perform(post("/transactions")
                        .contentType("application/json")
                        .header(IDEMPOTENCY_HEADER, idempotencyKey)
                        .content(transferJson(sourceId, destinationId, amount)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(
                result.getResponse().getContentAsString(),
                TransactionResponse.class
        );
    }

    private void assertLedgerEffect(UUID transactionId, UUID accountId, String amount) {
        BigDecimal actual = jdbcTemplate.queryForObject(
                "SELECT amount FROM ledger_entries WHERE transaction_id = ? AND account_id = ?",
                BigDecimal.class,
                transactionId,
                accountId
        );
        assertThat(actual).isEqualByComparingTo(amount);
    }

    private void awaitBlockedClaims(int expectedCount) throws InterruptedException {
        long timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < timeout) {
            Integer blockedClaims = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM pg_stat_activity
                    WHERE datname = current_database()
                      AND state = 'active'
                      AND wait_event_type = 'Lock'
                      AND query LIKE '%INSERT INTO ledger_transactions%'
                    """,
                    Integer.class
            );
            if (blockedClaims != null && blockedClaims >= expectedCount) {
                return;
            }
            Thread.sleep(10);
        }
        Integer blockedClaims = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM pg_stat_activity
                WHERE datname = current_database()
                  AND state = 'active'
                  AND wait_event_type = 'Lock'
                  AND query LIKE '%INSERT INTO ledger_transactions%'
                """,
                Integer.class
        );
        assertThat(blockedClaims).isGreaterThanOrEqualTo(expectedCount);
    }

    private void assertBalance(UUID accountId, String expectedBalance) {
        Account account = accountRepository.findById(Objects.requireNonNull(accountId)).orElseThrow();
        assertThat(account.getBalance()).isEqualByComparingTo(expectedBalance);
    }

    private BigDecimal entryAmount(UUID accountId) {
        return jdbcTemplate.queryForObject(
                "SELECT amount FROM ledger_entries WHERE account_id = ?",
                BigDecimal.class,
                accountId
        );
    }

    private void assertBalanceMatchesOpeningPlusEntries(UUID accountId) {
        BigDecimal expected = jdbcTemplate.queryForObject(
                """
                SELECT a.opening_balance + COALESCE(SUM(e.amount), 0)
                FROM accounts a
                LEFT JOIN ledger_entries e ON e.account_id = a.id
                WHERE a.id = ?
                GROUP BY a.id, a.opening_balance
                """,
                BigDecimal.class,
                accountId
        );
        BigDecimal stored = jdbcTemplate.queryForObject(
                "SELECT balance FROM accounts WHERE id = ?",
                BigDecimal.class,
                accountId
        );
        assertThat(stored).isEqualByComparingTo(expected);
    }

    private void installFailingLedgerEntryTrigger() {
        jdbcTemplate.execute(
                """
                CREATE OR REPLACE FUNCTION fail_ledger_entry_insert()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    RAISE EXCEPTION 'forced ledger entry insert failure';
                END;
                $$
                """
        );
        jdbcTemplate.execute(
                """
                CREATE TRIGGER fail_ledger_entry_insert
                BEFORE INSERT ON ledger_entries
                FOR EACH ROW EXECUTE FUNCTION fail_ledger_entry_insert()
                """
        );
    }

    private void removeFailingLedgerEntryTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_ledger_entry_insert ON ledger_entries");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_ledger_entry_insert()");
    }

    private void installBlockingIdempotencyClaimTrigger() {
        jdbcTemplate.execute(
                """
                CREATE OR REPLACE FUNCTION hold_idempotency_claim()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    IF NEW.idempotency_key = 'blocked-claim-key' THEN
                        PERFORM pg_advisory_xact_lock(8675309::bigint);
                    END IF;
                    RETURN NEW;
                END;
                $$
                """
        );
        jdbcTemplate.execute(
                """
                CREATE TRIGGER hold_idempotency_claim
                AFTER INSERT ON ledger_transactions
                FOR EACH ROW EXECUTE FUNCTION hold_idempotency_claim()
                """
        );
    }

    private void removeBlockingIdempotencyClaimTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS hold_idempotency_claim ON ledger_transactions");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS hold_idempotency_claim()");
    }

    private static @NonNull String transferJson(
            UUID sourceId,
            UUID destinationId,
            String amount
    ) {
        return Objects.requireNonNull("""
                {
                  "sourceAccountId": "%s",
                  "destinationAccountId": "%s",
                  "amount": "%s"
                }
                """.formatted(sourceId, destinationId, amount));
    }

    private static List<UUID> temporaryAccountIds() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ids.add(UUID.fromString(
                    "00000000-0000-0000-0000-%012d".formatted(100 + i)
            ));
        }
        return List.copyOf(ids);
    }

    private record ConcurrentAttempt(TransferResult result, RuntimeException failure) {
    }

    private record ReversalAttempt(TransferResult result, RuntimeException failure) {
    }
}
