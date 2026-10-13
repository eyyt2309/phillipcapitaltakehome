# Ledger System Technical Design

## 1. Goal and scope

Build a small, auditable REST ledger in two days using Java 21, Spring Boot, Spring Web, Spring Data JPA, PostgreSQL, and Flyway. Demonstrate atomic transfers, safe concurrent updates, idempotency, immutable history, reversals, foreign exchange, and reconciliation without introducing infrastructure that does not improve correctness for this exercise.

Use Bean Validation for request constraints, JUnit 5 for tests, Testcontainers with real PostgreSQL for integration/concurrency tests, and Docker Compose for local development. Store timestamps as UTC `TIMESTAMPTZ`. Represent money and rates only with Java `BigDecimal` and PostgreSQL `NUMERIC`; never use `double` or `float`.

## 2. Architecture

Use one Spring Boot application and one PostgreSQL database:

```text
HTTP client
    |
    v
Controller
    |
    v
Application service
    |
    v
Repository
    |
    v
PostgreSQL
```

- **Controllers** validate HTTP-level input, map DTOs, and translate domain errors to HTTP responses. They contain no financial posting logic.
- **Application services** own transfers, reversals, idempotency, exchange-rate application, and reconciliation orchestration. Financial writes run inside Spring `@Transactional` methods.
- **Repositories** own persistence, account row locks, transaction queries, and ledger-entry queries. Use Spring Data JPA for normal persistence and a small explicit PostgreSQL native query where `ON CONFLICT` is needed for race-safe idempotency.
- **PostgreSQL** is the source of truth. Posted transactions and ledger entries are append-only. The account balance is a projection updated atomically with entries and verified by reconciliation.
- **Flyway** manages schema changes. Docker Compose starts the application dependencies for local use.

Keep financial logic in a focused `TransactionService` initially. Extract a small component only when it makes a concrete rule easier to test or understand.

## 3. Core data model

Start with three tables: `accounts`, `ledger_transactions`, and `ledger_entries`. Do not add currency, snapshot, or reconciliation-run tables for this take-home.

Amounts use `NUMERIC(19,4)` and rates use `NUMERIC(19,8)`. This supports the four fractional digits chosen for the exercise. Account currency and supported scale are fixed when an account is created; validate amount precision against the exercise's configured currency rules and never silently round transfer input. FX destination amounts are rounded to four fractional digits with `RoundingMode.HALF_EVEN`.

### Proposed PostgreSQL schema

```sql
CREATE TABLE accounts (
    id              UUID PRIMARY KEY,
    currency        VARCHAR(3) NOT NULL,
    opening_balance NUMERIC(19,4) NOT NULL DEFAULT 0,
    balance         NUMERIC(19,4) NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (opening_balance >= 0),
    CHECK (balance >= 0)
);

CREATE TABLE ledger_transactions (
    id                     UUID PRIMARY KEY,
    source_account_id      UUID NOT NULL REFERENCES accounts(id),
    destination_account_id UUID NOT NULL REFERENCES accounts(id),
    source_amount          NUMERIC(19,4) NOT NULL CHECK (source_amount > 0),
    source_currency        VARCHAR(3) NOT NULL,
    destination_amount     NUMERIC(19,4) NOT NULL CHECK (destination_amount > 0),
    destination_currency   VARCHAR(3) NOT NULL,
    exchange_rate          NUMERIC(19,8),
    transaction_type       VARCHAR(20) NOT NULL
                           CHECK (transaction_type IN ('TRANSFER', 'REVERSAL')),
    idempotency_key        VARCHAR(255) NOT NULL UNIQUE,
    request_hash           CHAR(64) NOT NULL,
    reversal_of            UUID UNIQUE REFERENCES ledger_transactions(id),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (source_account_id <> destination_account_id),
    CHECK (
        (transaction_type = 'TRANSFER' AND reversal_of IS NULL)
        OR
        (transaction_type = 'REVERSAL' AND reversal_of IS NOT NULL)
    ),
    CHECK (
        (source_currency = destination_currency AND exchange_rate IS NULL)
        OR
        (source_currency <> destination_currency AND exchange_rate > 0)
    )
);

CREATE TABLE ledger_entries (
    id              UUID PRIMARY KEY,
    transaction_id  UUID NOT NULL REFERENCES ledger_transactions(id),
    account_id      UUID NOT NULL REFERENCES accounts(id),
    amount          NUMERIC(19,4) NOT NULL CHECK (amount <> 0),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ledger_entries_account_time_idx
    ON ledger_entries (account_id, created_at, transaction_id);
CREATE INDEX ledger_entries_transaction_idx
    ON ledger_entries (transaction_id);
```

`ledger_entries.amount` is signed: negative is a debit and positive is a credit. Its currency is the immutable currency of the referenced account. Account currency must not be changed after creation.

Each posted transaction has exactly two entries: a negative entry for the source account and a positive entry for the destination account. For same-currency transfers, the entries sum to zero. For FX transfers they are in different currencies and must **not** be added together or described as numerically balancing.

The schema permits future transaction types, but the check constraint is updated by a Flyway migration when a new type is actually implemented. Do not implement fees, deposits, withdrawals, or adjustments now.

### Opening balances

`opening_balance` is stored directly to keep account provisioning small. Reconciliation calculates:

```text
expected account balance = opening_balance + SUM(ledger_entries.amount)
```

Opening balances are fixed once an account has activity. In a production ledger, initial funding would preferably be represented by balanced ledger entries. Separate clearing/funding accounts are not needed for this exercise.

## 4. API

Use JSON decimal strings in examples and DTOs so clients are not encouraged to parse money as binary floating point. Bean Validation checks required fields, positive amounts, currency format, and request bounds; the service enforces relational and financial rules.

### `POST /transactions`

Requires `Idempotency-Key`. Amount is denominated in the source account currency. For same-currency transfers, destination amount equals amount. For FX, the service obtains the rate from `ExchangeRateProvider` and calculates the destination amount.

Request:

```json
{
  "sourceAccountId": "6b6c8742-a2a2-4d4c-b0d7-174b1a4fa2ea",
  "destinationAccountId": "7ccbeb0f-9fed-40c9-9763-b912a544ee4e",
  "amount": "100.00"
}
```

First successful submission returns `201 Created`. A retry with the same key and same request returns the original transaction (`200 OK`) without moving money again. Reusing a key for a different request returns `409 Conflict`.

```json
{
  "transactionId": "f3dbb058-9cd0-4309-975b-86fd2d67e30c",
  "type": "TRANSFER",
  "status": "POSTED",
  "sourceAccountId": "6b6c8742-a2a2-4d4c-b0d7-174b1a4fa2ea",
  "sourceAmount": "100.00",
  "sourceCurrency": "USD",
  "destinationAccountId": "7ccbeb0f-9fed-40c9-9763-b912a544ee4e",
  "destinationAmount": "135.00",
  "destinationCurrency": "SGD",
  "exchangeRate": "1.35000000",
  "createdAt": "2026-10-12T13:30:00Z"
}
```

Reject self-transfers, non-positive or over-precision amounts, unsupported currency pairs, insufficient funds, and attempts to transfer between accounts of the same currency when currency values differ (same-currency transfers always use the same amount).

### `GET /accounts/{id}`

Returns the current balance:

```json
{
  "accountId": "6b6c8742-a2a2-4d4c-b0d7-174b1a4fa2ea",
  "currency": "SGD",
  "balance": "900.00"
}
```

An unknown account returns `404 Not Found`.

### `GET /accounts/{id}/transactions`

Returns the transactions affecting the account in ascending `created_at, id` order. Cursor pagination is not required. A simple Spring `Pageable` may be used if convenient, but must not become a separate feature project. Each item shows signed account effect and reversal linkage where relevant.

```json
{
  "items": [
    {
      "transactionId": "f3dbb058-9cd0-4309-975b-86fd2d67e30c",
      "type": "TRANSFER",
      "amount": "-100.00",
      "currency": "SGD",
      "createdAt": "2026-10-12T13:30:00Z"
    }
  ]
}
```

### `POST /transactions/{id}/reversals`

Requires `Idempotency-Key`; creates a full reversal as a new transaction and returns `201 Created`. It references the original using `reversal_of`. Only one full reversal is allowed per transaction.

The reversal transaction retains the original transfer's account and amount/rate metadata for auditability; its two new ledger entries have the opposite signs of the original entries. The entries, rather than the transaction's descriptive source/destination fields, express the reversal's balance effects.

Example response:

```json
{
  "transactionId": "6d7fd2e1-4f34-468b-9c28-6971dc637e27",
  "type": "REVERSAL",
  "reversalOf": "f3dbb058-9cd0-4309-975b-86fd2d67e30c",
  "status": "POSTED",
  "createdAt": "2026-10-12T14:00:00Z"
}
```

### `GET /reconciliation`

Reconciliation is run on demand, including at month-end; there is no scheduled job. It optionally accepts `asOfDate=YYYY-MM-DD`. Without it, compare current stored balances with current ledger-derived balances. With it, calculate ledger-derived account balances through the end of that UTC date. Historical stored-balance snapshots and comparison against them are intentionally out of scope; a past-date report is a ledger reconstruction only.

Return account-level expected/stored balance and discrepancy details. Include per-transaction invariant failures and per-currency aggregate information where meaningful. The endpoint is read-only and does not repair data.

Example account discrepancy:

```json
{
  "accountId": "6b6c8742-a2a2-4d4c-b0d7-174b1a4fa2ea",
  "currency": "SGD",
  "storedBalance": "1000.0000",
  "expectedBalance": "980.0000",
  "difference": "20.0000",
  "status": "MISMATCH"
}
```

## 5. Atomic posting and concurrency

A transfer or reversal is performed by one `@Transactional` application-service operation. Any failure before commit rolls back account updates, transaction metadata, ledger entries, and the idempotency claim together.

For a transfer:

1. Validate the request and normalize values for request hashing.
2. Check for an already committed idempotency key. If its hash matches, return its transaction; if not, return `409 Conflict`.
3. Perform an unlocked account metadata lookup for both IDs. Return `404 Not Found` if either account is absent. This establishes the currencies needed to decide whether FX applies and prevents an early transaction insert from surfacing a foreign-key error instead of the expected not-found response.
4. If currencies differ, resolve the rate from `ExchangeRateProvider` using the looked-up currencies and calculate the destination amount. Resolve the rate before locking accounts; do not call an external provider while holding locks.
5. Make the race-safe idempotency claim by inserting the transaction row inside this database transaction, using the unique idempotency key. If another request won since step 2, compare its request hash and return the committed transaction or `409 Conflict` for a mismatch.
6. Lock both accounts before reading balances. Lock one account at a time in ascending UUID order using a JPA repository method annotated with `@Lock(PESSIMISTIC_WRITE)`.
7. Revalidate that both accounts still exist and that their currencies match the metadata used for rate resolution. Then check transfer rules and source funds using the locked rows.
8. Apply source and destination balance changes and persist the two `ledger_entries` referring to the claimed transaction row.
9. Commit and only then return success.

Always locking account pairs in the same order avoids transfer-vs-transfer deadlocks. PostgreSQL row locks serialize writes to shared accounts and prevent lost updates and overdrafts. Keep transactions short. No in-memory or distributed lock is used. Accounts are not hard-deleted by this application; the locked revalidation is retained to ensure the assumptions from the metadata lookup still hold before posting.

The transaction row is not visible to other database transactions until commit. If validation or posting fails after the idempotency claim, the entire transaction—including that row—rolls back. Every committed row therefore represents a fully posted transaction; a separate `PENDING` state is unnecessary. Basic account existence is deliberately checked before the claim; foreign keys remain the final integrity guard.

Core invariants:

- **Atomicity:** every write fully succeeds or has no financial effect.
- **No overdraft:** a customer source balance never becomes negative.
- **Same-currency conservation:** source change plus destination change is zero.
- **Balance consistency:** current balance equals opening balance plus all signed entries.
- **Immutability:** posted transaction rows and ledger entries are never updated or deleted.
- **Idempotency:** a repeated request cannot post twice.

## 6. Idempotency

For transfers in this implementation, canonicalize the amount to the schema scale of four decimal places without rounding, then encode it with exactly four fractional digits. Thus `100`, `100.0`, and `100.0000` are equivalent for request hashing; values requiring rounding are rejected. The transfer canonical form is `TRANSFER|<source UUID>|<destination UUID>|<amount>`, encoded as UTF-8 and hashed with SHA-256 to a lowercase 64-character hexadecimal string. The caller key is limited to 255 non-blank characters, matching the database column.

Require a caller-generated `Idempotency-Key` on transfer and reversal endpoints. Store it with a unique constraint on `ledger_transactions`; store a SHA-256 hash of the canonical request fields: operation, account IDs, normalized source amount, and reversal target. Hash the client request, not a newly looked-up FX rate, so a replay still resolves to its original transaction if configured rates have changed.

First look up an already committed key and, when found, compare its hash: return the original transaction for a match or `409 Conflict` for a mismatch. For a key not yet present, perform basic account existence validation before attempting the race-safe claim. This ensures an unknown account returns `404 Not Found` rather than allowing the claim's foreign-key violation to replace the intended API error.

After those checks, use a PostgreSQL `INSERT ... ON CONFLICT DO NOTHING` claim in the same database transaction. For same-currency transfers, the claim is the complete transaction metadata row (with no FX rate); the database unique constraint is the final protection when concurrent requests both pass the initial lookup: only one claim can win. Under PostgreSQL `READ COMMITTED`, an insert that conflicts with an uncommitted claim waits for that transaction to finish. If the winner commits, the loser then performs its key lookup as a new statement with a fresh snapshot and reads the committed transaction; if the winner rolls back, the competing insert can claim and proceed. The transfer service explicitly uses `READ_COMMITTED` for this read-after-conflict behavior. The winning request's transaction row, account updates, and entries commit or roll back together. This small native repository operation is justified by the need to make the race-safe unique claim explicit; the rest of persistence uses Spring Data JPA. Failed requests roll back the claim, so they do not reserve a key permanently.

## 7. Reversals

A reversal is a new immutable transaction, never an edit or deletion. For a reversal row, `source_account_id`, `destination_account_id`, `source_amount`, `destination_amount`, currencies, and exchange rate are **metadata copied from the original transfer**, not a claim about the direction of the reversal. The actual reversal direction/effect is expressed by its new ledger entries: apply the exact opposite signed amounts to the same accounts. Store `transaction_type = 'REVERSAL'` and `reversal_of = original.id`; the unique constraint prevents a second full reversal.

Only posted transfers may be reversed. The reversal itself requires an idempotency key. **Assumption:** if reversing would make an account negative, reject with `409 Conflict`; partial reversals and reversal-of-reversal are out of scope. The original and reversal remain visible in history.

## 8. Foreign-exchange transfers

Define a small seam:

```java
public interface ExchangeRateProvider {
    BigDecimal getRate(String sourceCurrency, String destinationCurrency);
}
```

Use a deterministic in-memory/configuration-backed implementation for the take-home, with a small documented set of supported pairs and rates. It avoids a live API, network failures, caching, and rate-expiry scope while allowing a future provider to be substituted. Tests inject fixed rates. Unsupported pairs are rejected.

Interpret the rate as destination currency units per one source currency unit. Calculate:

```text
destinationAmount =
    sourceAmount * rate, rounded to 4 fractional digits (HALF_EVEN)
```

Persist the source amount/currency, destination amount/currency, and exact rate used on the transaction. These values never change. A cross-currency transfer writes source `-sourceAmount` and destination `+destinationAmount`; since they use different currencies, they are not summed numerically.

The FX invariants are that the source balance changes by exactly the recorded source amount, the destination balance changes by exactly the recorded destination amount, and the destination amount matches the recorded rate and rounding rule. A production double-entry ledger could add per-currency FX clearing accounts; they are not required here.

## 9. Reconciliation and conservation

`GET /reconciliation` calculates a report dynamically when requested. The operator runs it on demand at month-end; there is no scheduled job. No reconciliation-run table or historical balance snapshots are required. An `asOfDate` report reconstructs balances from ledger entries only and cannot compare them with a stored historical balance.

For every account, calculate:

```text
expected balance = opening_balance + SUM(ledger_entries.amount)
```

For current reconciliation, compare expected and stored `accounts.balance`, returning discrepancies without repairing them. For a supplied `asOfDate`, sum only entries through 23:59:59.999999 UTC on that date and report the reconstructed balance; historical comparison to a stored balance is not possible without snapshots.

For every transfer, verify there are exactly two entries with the expected account IDs, signs, currencies, and amounts. For a reversal, verify its entries are the sign-inverted effects of the referenced original transaction. Also verify:

- Every same-currency transaction's two entries sum to zero.
- Each entry belongs to one of the transaction's source/destination accounts and has the correct sign and amount.
- Each FX transaction's persisted rate produces its persisted destination amount under the defined scale and rounding rule.
- Current aggregate balances per currency equal the opening balances in that currency plus the signed ledger entries in that currency. Same-currency transfers have zero net effect; an FX transfer decreases the source-currency total and increases the destination-currency total by its recorded amounts. Do not claim cross-currency transfers conserve raw amounts within one currency.

The endpoint reports discrepancies with account/transaction identifiers and expected versus observed values. It never silently changes balances or entries.

## 10. Errors

Use a consistent RFC 9457 `ProblemDetail` response and a stable `code` extension, without SQL, stack traces, or internal database details:

```json
{
  "type": "about:blank",
  "title": "Insufficient funds",
  "status": 409,
  "detail": "The source account has insufficient funds.",
  "instance": "/transactions",
  "code": "INSUFFICIENT_FUNDS"
}
```

| Condition | HTTP |
|---|---:|
| Malformed body or Bean Validation failure | 400 |
| Account or transaction not found | 404 |
| Insufficient funds, self-transfer, duplicate reversal | 409 |
| Same idempotency key with a different request | 409 |
| Invalid amount, scale, or unsupported FX pair | 422 |
| Unexpected server error | 500 |

Log unexpected errors with a request/correlation ID and do not expose database messages to callers.

## 11. Testing strategy

Use JUnit 5 and PostgreSQL Testcontainers for repository, API, rollback, and concurrency integration tests. H2 is not the primary test database because it does not verify PostgreSQL row-locking or `ON CONFLICT` behavior. Docker Compose provides local PostgreSQL for manual development; Testcontainers gives repeatable automated integration tests.

- **Transfer:** start A=1000 SGD and B=500 SGD; transfer 100; verify balances 900/600 and entries -100/+100.
- **Validation/funds:** reject self-transfer, non-positive amount, bad precision, and insufficient funds; verify no persisted transaction/entries and unchanged balances.
- **Atomic rollback:** induce a persistence failure during posting and verify account balances, transaction, entries, and idempotency claim all roll back.
- **Idempotency:** sequential identical retries post once; same key with changed request conflicts; concurrent requests with one key create exactly one posting.
- **Concurrency:** twenty concurrent transfers of 100 from an account with 1000 result in ten successes, ten insufficient-funds failures, and a zero balance; concurrent credits to one destination are all reflected.
- **Reversal:** post and reverse a transfer; verify balances return to their prior values, the original is unchanged, and both transactions appear in history. Verify a second reversal is rejected.
- **FX:** verify a configured USD-to-SGD example (100 at 1.35 yields 135.0000), and verify the rate/amount are retained and reconciliation checks the rate relationship.
- **Queries/reconciliation:** verify stable account history ordering, current balance reconciliation, a historical `asOfDate` calculation, same-currency conservation, and detection of an intentionally corrupted balance fixture.
- **API errors:** verify status and `ProblemDetail` for not-found, conflicts, and validation failures.

## 12. Assumptions and trade-offs

- Accounts are provisioned by seed/test data or a minimal setup mechanism; public account creation/funding is not required by the assignment.
- Opening balances are stored directly on accounts for simplicity. A production system would represent funding with balanced ledger entries.
- Account currency and opening balance are immutable after account activity starts. The exercise supports currencies with no more than four fractional digits.
- The rate provider is deterministic and local. External rate sourcing, rate freshness/expiry, market verification, and fees are out of scope.
- Full reversals only; a reversal that would violate no-overdraft is rejected.
- The ledger has exactly two entries per transfer/reversal. The schema and service can evolve to more entries for fees or per-currency clearing, but those features are not prebuilt.
- `balance` is a cached projection for efficient reads; entries are the audit trail and reconciliation source.
- Reconciliation is computed on demand. Persisted runs, snapshots, scheduling, dashboards, authentication, authorization, account ownership, and audit streaming are future improvements.
- Transaction type currently supports `TRANSFER` and `REVERSAL`; future types can be introduced with a migration and service behavior without redesigning the three core tables.

## 13. Proposed package structure

```text
com.example.ledger
├── LedgerApplication.java
├── account
│   ├── Account.java
│   ├── AccountController.java
│   └── AccountRepository.java
├── transaction
│   ├── LedgerTransaction.java
│   ├── LedgerEntry.java
│   ├── TransactionController.java
│   ├── TransactionService.java
│   ├── TransactionRepository.java
│   ├── LedgerEntryRepository.java
│   └── dto/
├── fx
│   └── ExchangeRateProvider.java
├── reconciliation
│   ├── ReconciliationController.java
│   └── ReconciliationService.java
└── common
    ├── ApiExceptionHandler.java
    └── domain exceptions
```

Keep transfer and reversal posting in `TransactionService` until a focused extraction materially improves clarity or testing. Do not split into many services pre-emptively.

## 14. Recommended implementation order

1. Bootstrap Java 21/Spring Boot with Web, Data JPA, Bean Validation, Flyway, PostgreSQL, JUnit 5, and Testcontainers; add Docker Compose for local PostgreSQL.
2. Create the three-table Flyway migration and deterministic account fixtures with opening balances.
3. Implement `GET /accounts/{id}` and basic account history.
4. Implement same-currency transfer validation, deterministic pessimistic account locks, balance updates, transaction row, and signed entries in one `@Transactional` service method.
5. Add Testcontainers coverage for success, insufficient funds, rollback, concurrent spending, and concurrent credits.
6. Add idempotency key/hash and PostgreSQL unique-claim behavior; test sequential and concurrent replay.
7. Implement full reversals with uniqueness and no-overdraft checks; test immutability and duplicate reversal.
8. Add the deterministic `ExchangeRateProvider`, FX calculation/persistence, and FX tests.
9. Implement dynamic current/month-end reconciliation and discrepancy tests.
10. Finalize `ProblemDetail` mapping, verify all tests against PostgreSQL, and document local run/test commands.

## 15. Future extensions

If the requirements grow, add only what the new behavior needs: currency configuration, per-currency FX clearing accounts, additional transaction types and entries, reconciliation snapshots/runs, external FX provider, scheduled reconciliation, fees, pagination, authentication/authorization, and operational reporting. These are not prerequisites for the take-home and should not delay the correct three-table implementation.
