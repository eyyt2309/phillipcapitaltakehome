package com.example.ledger.reconciliation;

import com.example.ledger.transaction.TransactionResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ReconciliationApiIntegrationTest {

    private static final UUID ACCOUNT_A = Objects.requireNonNull(
            UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final UUID ACCOUNT_B = Objects.requireNonNull(
            UUID.fromString("00000000-0000-0000-0000-000000000002"));
    private static final UUID ACCOUNT_C = Objects.requireNonNull(
            UUID.fromString("00000000-0000-0000-0000-000000000003"));

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
    private ObjectMapper objectMapper;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM ledger_entries");
        jdbcTemplate.update("DELETE FROM ledger_transactions");
        jdbcTemplate.update("UPDATE accounts SET balance = opening_balance");
    }

    @Test
    void cleanReconciliationReturnsEveryAccountAndIndependentCurrencyTotals() throws Exception {
        MvcResult result = mockMvc.perform(get("/reconciliation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOfDate").doesNotExist())
                .andExpect(jsonPath("$.historicalReconstructionOnly").value(false))
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.accountResults.length()").value(3))
                .andExpect(jsonPath("$.currencyTotals.length()").value(2))
                .andExpect(jsonPath("$.transactionChecks.checked").value(0))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.at("/currencyTotals/0/currency").asText()).isEqualTo("SGD");
        assertThat(body.at("/currencyTotals/0/openingBalanceTotal").asText())
                .isEqualTo("1500.0000");
        assertThat(body.at("/currencyTotals/1/currency").asText()).isEqualTo("USD");
        assertThat(body.at("/currencyTotals/1/openingBalanceTotal").asText())
                .isEqualTo("1000.0000");
        assertThat(body.has("total")).isFalse();
        assertThat(body.at("/accountResults/0/openingBalance").asText()).isEqualTo("1000.0000");
        assertThat(body.at("/accountResults/0/ledgerEffect").asText()).isEqualTo("0.0000");
    }

    @Test
    void sameCurrencyTransferPassesAccountCurrencyAndTransactionChecks() throws Exception {
        postTransfer(ACCOUNT_A, ACCOUNT_B, "100.0000", "reconcile-sgd");

        mockMvc.perform(get("/reconciliation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.transactionChecks.checked").value(1))
                .andExpect(jsonPath("$.transactionChecks.discrepancies").isEmpty())
                .andExpect(jsonPath("$.accountResults[0].expectedBalance").value("900.0000"))
                .andExpect(jsonPath("$.accountResults[0].storedBalance").value("900.0000"))
                .andExpect(jsonPath("$.currencyTotals[0].ledgerEffectTotal").value("0.0000"))
                .andExpect(jsonPath("$.currencyTotals[0].status").value("OK"));
    }

    @Test
    void fxTransferReconcilesPerCurrencyWithoutCombiningValues() throws Exception {
        postTransfer(ACCOUNT_C, ACCOUNT_B, "100.0000", "reconcile-fx");

        MvcResult result = mockMvc.perform(get("/reconciliation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.transactionChecks.checked").value(1))
                .andExpect(jsonPath("$.transactionChecks.discrepancies").isEmpty())
                .andReturn();
        JsonNode currencies = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("currencyTotals");
        assertThat(currencies).hasSize(2);
        assertThat(currencies.get(0).get("currency").asText()).isEqualTo("SGD");
        assertThat(currencies.get(0).get("ledgerEffectTotal").asText()).isEqualTo("135.0000");
        assertThat(currencies.get(0).get("expectedBalanceTotal").asText()).isEqualTo("1635.0000");
        assertThat(currencies.get(1).get("currency").asText()).isEqualTo("USD");
        assertThat(currencies.get(1).get("ledgerEffectTotal").asText()).isEqualTo("-100.0000");
        assertThat(currencies.get(1).get("expectedBalanceTotal").asText()).isEqualTo("900.0000");
    }

    @Test
    void reversalReconcilesAgainstOriginalFxEntriesAndCopiedMetadata() throws Exception {
        TransactionResponse original = postTransfer(
                ACCOUNT_C, ACCOUNT_B, "100.0000", "reconcile-reversal-original"
        );
        MvcResult reversed = mockMvc.perform(post(
                                "/transactions/{transactionId}/reversals",
                                original.transactionId()
                        )
                        .header("Idempotency-Key", "reconcile-reversal"))
                .andExpect(status().isCreated())
                .andReturn();
        assertThat(reversed.getResponse().getContentAsString()).contains("\"exchangeRate\":\"1.35000000\"");

        mockMvc.perform(get("/reconciliation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.transactionChecks.checked").value(2))
                .andExpect(jsonPath("$.transactionChecks.discrepancies").isEmpty())
                .andExpect(jsonPath("$.accountResults[0].expectedBalance").value("1000.0000"))
                .andExpect(jsonPath("$.accountResults[1].expectedBalance").value("500.0000"));
    }

    @Test
    void corruptStoredBalanceReturnsOkWithAccountDiscrepancy() throws Exception {
        jdbcTemplate.update("UPDATE accounts SET balance = 1001.0000 WHERE id = ?", ACCOUNT_A);

        mockMvc.perform(get("/reconciliation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISCREPANCY"))
                .andExpect(jsonPath("$.accountResults[0].status").value("DISCREPANCY"))
                .andExpect(jsonPath("$.accountResults[0].difference").value("1.0000"))
                .andExpect(jsonPath("$.transactionChecks.discrepancies[0].code")
                        .value("ACCOUNT_BALANCE_MISMATCH"))
                .andExpect(jsonPath("$.transactionChecks.discrepancies[0].accountId")
                        .value(ACCOUNT_A.toString()));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT balance FROM accounts WHERE id = ?",
                java.math.BigDecimal.class,
                ACCOUNT_A
        )).isEqualByComparingTo("1001.0000");
    }

    @Test
    void corruptSameCurrencyEntryIsReportedWithoutRepair() throws Exception {
        TransactionResponse transfer = postTransfer(
                ACCOUNT_A, ACCOUNT_B, "100.0000", "reconcile-corrupt-sgd"
        );
        jdbcTemplate.update(
                """
                UPDATE ledger_entries
                SET amount = 90.0000
                WHERE transaction_id = ? AND account_id = ?
                """,
                transfer.transactionId(),
                ACCOUNT_B
        );

        mockMvc.perform(get("/reconciliation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISCREPANCY"))
                .andExpect(jsonPath("$.transactionChecks.discrepancies[*].code")
                        .value(org.hamcrest.Matchers.hasItems(
                                "SAME_CURRENCY_NOT_BALANCED",
                                "SAME_CURRENCY_ENTRY_MISMATCH"
                        )));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT amount FROM ledger_entries WHERE transaction_id = ? AND account_id = ?",
                java.math.BigDecimal.class,
                transfer.transactionId(),
                ACCOUNT_B
        )).isEqualByComparingTo("90.0000");
    }

    @Test
    void corruptFxDestinationAmountIsReported() throws Exception {
        TransactionResponse transfer = postTransfer(
                ACCOUNT_C, ACCOUNT_B, "100.0000", "reconcile-corrupt-fx"
        );
        jdbcTemplate.update(
                "UPDATE ledger_transactions SET destination_amount = 134.0000 WHERE id = ?",
                transfer.transactionId()
        );

        mockMvc.perform(get("/reconciliation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISCREPANCY"))
                .andExpect(jsonPath("$.transactionChecks.discrepancies[*].code")
                        .value(org.hamcrest.Matchers.hasItems(
                                "FX_DESTINATION_AMOUNT_MISMATCH",
                                "FX_DESTINATION_ENTRY_MISMATCH"
                        )));
    }

    @Test
    void corruptReversalEntryIsReportedAsNotAnExactInverse() throws Exception {
        TransactionResponse original = postTransfer(
                ACCOUNT_A, ACCOUNT_B, "100.0000", "reconcile-corrupt-reversal-original"
        );
        MvcResult reversalResult = mockMvc.perform(post(
                                "/transactions/{transactionId}/reversals",
                                original.transactionId()
                        )
                        .header("Idempotency-Key", "reconcile-corrupt-reversal"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode reversal = objectMapper.readTree(reversalResult.getResponse().getContentAsString());
        UUID reversalId = UUID.fromString(reversal.get("transactionId").asText());
        jdbcTemplate.update(
                "UPDATE ledger_entries SET amount = 99.0000 WHERE transaction_id = ? AND account_id = ?",
                reversalId,
                ACCOUNT_A
        );

        mockMvc.perform(get("/reconciliation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISCREPANCY"))
                .andExpect(jsonPath("$.transactionChecks.discrepancies[*].code")
                        .value(org.hamcrest.Matchers.hasItem("REVERSAL_ENTRY_MISMATCH")));
    }

    @Test
    void historicalReconciliationIncludesOnlyEntriesBeforeNextUtcDay() throws Exception {
        TransactionResponse early = postTransfer(
                ACCOUNT_A, ACCOUNT_B, "100.0000", "reconcile-historical-early"
        );
        TransactionResponse late = postTransfer(
                ACCOUNT_A, ACCOUNT_B, "50.0000", "reconcile-historical-late"
        );
        Instant beforeCutoff = Instant.parse("2026-10-30T12:00:00Z");
        Instant afterCutoff = Instant.parse("2026-11-01T00:00:00Z");
        setTransactionAndEntriesTime(early.transactionId(), beforeCutoff);
        setTransactionAndEntriesTime(late.transactionId(), afterCutoff);

        mockMvc.perform(get("/reconciliation").param("asOfDate", "2026-10-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOfDate").value("2026-10-30"))
                .andExpect(jsonPath("$.historicalReconstructionOnly").value(true))
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.transactionChecks.checked").value(1))
                .andExpect(jsonPath("$.accountResults[0].expectedBalance").value("900.0000"))
                .andExpect(jsonPath("$.accountResults[0].storedBalance").doesNotExist())
                .andExpect(jsonPath("$.accountResults[0].difference").doesNotExist())
                .andExpect(jsonPath("$.currencyTotals[0].storedBalanceTotal").doesNotExist())
                .andExpect(jsonPath("$.currencyTotals[0].difference").doesNotExist());
    }

    @Test
    void invalidAsOfDateReturnsCleanBadRequest() throws Exception {
        mockMvc.perform(get("/reconciliation").param("asOfDate", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AS_OF_DATE"))
                .andExpect(jsonPath("$.detail").value("asOfDate must use YYYY-MM-DD format."));
    }

    private TransactionResponse postTransfer(
            UUID source,
            UUID destination,
            String amount,
            String idempotencyKey
    ) throws Exception {
        MvcResult result = mockMvc.perform(post("/transactions")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType("application/json")
                        .content("""
                                {
                                  "sourceAccountId": "%s",
                                  "destinationAccountId": "%s",
                                  "amount": "%s"
                                }
                                """.formatted(source, destination, amount)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(
                result.getResponse().getContentAsString(),
                TransactionResponse.class
        );
    }

    private void setTransactionAndEntriesTime(UUID transactionId, Instant createdAt) {
        jdbcTemplate.update(
                "UPDATE ledger_transactions SET created_at = ? WHERE id = ?",
                Timestamp.from(createdAt),
                transactionId
        );
        jdbcTemplate.update(
                "UPDATE ledger_entries SET created_at = ? WHERE transaction_id = ?",
                Timestamp.from(createdAt),
                transactionId
        );
    }
}
