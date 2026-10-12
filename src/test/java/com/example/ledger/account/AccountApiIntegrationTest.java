package com.example.ledger.account;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AccountApiIntegrationTest {

    private static final UUID ACCOUNT_A =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_B =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ACCOUNT_C =
            UUID.fromString("00000000-0000-0000-0000-000000000003");

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

    @Test
    void applicationStartsAndFlywayCreatesAllThreeTables() {
        assertThat(jdbcTemplate.queryForObject("SELECT version()", String.class))
                .contains("PostgreSQL");
        assertThat(accountRepository.count()).isEqualTo(3);
        assertThat(tableExists("accounts")).isTrue();
        assertThat(tableExists("ledger_transactions")).isTrue();
        assertThat(tableExists("ledger_entries")).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true",
                Integer.class
        )).isEqualTo(2);
    }

    @Test
    void migrationSeedsDeterministicAccountsWithExpectedBalances() {
        assertSeededAccount(ACCOUNT_A, "SGD", "1000.0000");
        assertSeededAccount(ACCOUNT_B, "SGD", "500.0000");
        assertSeededAccount(ACCOUNT_C, "USD", "1000.0000");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_transactions",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries",
                Integer.class
        )).isZero();
    }

    @Test
    void getsExistingAccount() throws Exception {
        mockMvc.perform(get("/accounts/{id}", ACCOUNT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(ACCOUNT_A.toString()))
                .andExpect(jsonPath("$.currency").value("SGD"))
                .andExpect(jsonPath("$.balance").value("1000.0000"));
    }

    @Test
    void returnsProblemDetailForUnknownAccount() throws Exception {
        UUID unknownId = UUID.fromString("00000000-0000-0000-0000-000000000099");

        mockMvc.perform(get("/accounts/{id}", unknownId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Account not found"))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @Transactional
    void exchangeRateConstraintAcceptsSameCurrencyTransferWithoutRate() {
        UUID transactionId = UUID.randomUUID();

        insertTransfer(transactionId, ACCOUNT_A, ACCOUNT_B, "SGD", "SGD", null);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT exchange_rate FROM ledger_transactions WHERE id = ?",
                BigDecimal.class,
                transactionId
        )).isNull();
    }

    @Test
    @Transactional
    void exchangeRateConstraintAcceptsCrossCurrencyTransferWithPositiveRate() {
        UUID transactionId = UUID.randomUUID();

        insertTransfer(transactionId, ACCOUNT_A, ACCOUNT_C, "SGD", "USD", "0.74000000");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT exchange_rate FROM ledger_transactions WHERE id = ?",
                BigDecimal.class,
                transactionId
        )).isEqualByComparingTo("0.74000000");
    }

    @Test
    void exchangeRateConstraintRejectsMissingRateForCrossCurrencyTransfer() {
        assertExchangeRateConstraintRejects(
                ACCOUNT_A, ACCOUNT_C, "SGD", "USD", null
        );
    }

    @Test
    void exchangeRateConstraintRejectsNonPositiveRateForCrossCurrencyTransfer() {
        assertExchangeRateConstraintRejects(
                ACCOUNT_A, ACCOUNT_C, "SGD", "USD", "0"
        );
        assertExchangeRateConstraintRejects(
                ACCOUNT_A, ACCOUNT_C, "SGD", "USD", "-1"
        );
    }

    @Test
    void exchangeRateConstraintRejectsRateForSameCurrencyTransfer() {
        assertExchangeRateConstraintRejects(
                ACCOUNT_A, ACCOUNT_B, "SGD", "SGD", "1"
        );
    }

    private void assertSeededAccount(UUID id, String currency, String balance) {
        Account account = accountRepository.findById(Objects.requireNonNull(id)).orElseThrow();
        assertThat(account.getCurrency()).isEqualTo(currency);
        assertThat(account.getOpeningBalance()).isEqualByComparingTo(new BigDecimal(balance));
        assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal(balance));
    }

    private void assertExchangeRateConstraintRejects(
            UUID sourceAccountId,
            UUID destinationAccountId,
            String sourceCurrency,
            String destinationCurrency,
            String exchangeRate
    ) {
        UUID transactionId = UUID.randomUUID();

        Throwable exception = catchThrowable(() -> insertTransfer(
                transactionId,
                sourceAccountId,
                destinationAccountId,
                sourceCurrency,
                destinationCurrency,
                exchangeRate
        ));

        assertThat(exception).isInstanceOf(DataIntegrityViolationException.class);
        Throwable rootCause = exception;
        while (rootCause.getCause() != null) {
            rootCause = rootCause.getCause();
        }
        assertThat(rootCause).isInstanceOf(SQLException.class);
        SQLException sqlException = (SQLException) rootCause;
        assertThat(sqlException.getSQLState()).isEqualTo("23514");
        assertThat(sqlException.getMessage())
                .contains("ledger_transactions_exchange_rate_consistency");
    }

    private void insertTransfer(
            UUID transactionId,
            UUID sourceAccountId,
            UUID destinationAccountId,
            String sourceCurrency,
            String destinationCurrency,
            String exchangeRate
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO ledger_transactions (
                    id, source_account_id, destination_account_id,
                    source_amount, source_currency, destination_amount,
                    destination_currency, exchange_rate, transaction_type,
                    idempotency_key, request_hash, reversal_of
                )
                VALUES (?, ?, ?, 10.0000, ?, 10.0000, ?, ?::numeric,
                        'TRANSFER', ?, ?, NULL)
                """,
                transactionId,
                sourceAccountId,
                destinationAccountId,
                sourceCurrency,
                destinationCurrency,
                exchangeRate,
                transactionId.toString(),
                "0".repeat(64)
        );
    }

    private boolean tableExists(String tableName) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.tables
                    WHERE table_schema = 'public' AND table_name = ?
                )
                """,
                Boolean.class,
                tableName
        ));
    }
}
