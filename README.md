# Internal Ledger System

A REST API for transferring money between accounts. This Spring Boot take-home
focuses on atomic posting, concurrency safety, idempotent requests, immutable
transaction history, full reversals, and deterministic foreign-exchange
transfers.

## Assignment scope

The implementation covers:

- Atomic transfers that debit and credit accounts together.
- Insufficient-funds and self-transfer checks.
- Account balance and transaction-history queries.
- Idempotency keys for transfers and reversals.
- Full, immutable reversal transactions.
- USD/SGD transfers using a deterministic local exchange-rate provider.
- PostgreSQL-backed persistence and integration tests using Testcontainers.

There is no public account-creation or funding API. The database migration
seeds development accounts; balances can also be initialized in test fixtures.
Deposits, withdrawals, partial reversals, authentication, and authorization are
outside this take-home's implemented scope.

Reconciliation is implemented as a read-only, on-demand endpoint. It checks
account balances against ledger entries and validates transfer, FX, and
reversal invariants. Historical requests reconstruct balances from ledger
entries; historical stored-balance snapshots are not maintained.

## Technology

- Java 21
- Spring Boot 3.5
- Spring Web, Spring Data JPA, and Bean Validation
- PostgreSQL 17
- Flyway database migrations
- JUnit 5, Spring Boot Test, and Testcontainers
- Docker Compose for local PostgreSQL

## Architecture

```text
HTTP client
    |
    v
Spring MVC controllers
    |
    v
Application services
    |
    v
Spring Data JPA / focused SQL queries
    |
    v
PostgreSQL <--- Flyway migrations
```

Controllers validate HTTP input and format responses. `TransactionService`
owns atomic transfers, reversals, idempotency, and FX application.
`ReconciliationService` performs read-only ledger checks. Repositories handle
account locks, persistence, and reconciliation queries.

The database uses three tables:

- `accounts` stores the account currency, opening balance, and current balance.
- `ledger_transactions` stores immutable transfer/reversal metadata, including
  idempotency data and any exchange rate used.
- `ledger_entries` stores signed account effects: debits are negative and
  credits are positive.

Amounts use `NUMERIC(19,4)` and exchange rates use `NUMERIC(19,8)`. The
application uses `BigDecimal` rather than binary floating-point values. A
transfer records both account balance changes and its two ledger entries in
one transaction. Concurrent transfers lock both accounts in ascending UUID
order, preventing lost updates and avoiding transfer-to-transfer deadlocks.

For same-currency transfers, the source debit and destination credit are equal
and offset each other. For cross-currency transfers, the entries are in
different currencies and must not be summed as if they were the same unit.
Account history returns each entry's signed amount in that account's currency.

Transaction and ledger-entry rows are append-only by application behavior.
Transfer and reversal posting is atomic: transaction metadata, account
projections, ledger entries, and the idempotency claim commit or roll back
together.

### Idempotency and reversals

Every transfer and reversal requires an `Idempotency-Key` header. Repeating a
request with the same key and same operation details returns the original
transaction without posting again. Reusing a key for different details returns
`409 Conflict`. The key is limited to 255 non-blank characters.

A reversal is a new transaction linked to the original; the original
transaction and entries are not edited or deleted. A transfer can only be
reversed once, and the reversal is rejected if it would make an account
negative. Partial reversals and reversal-of-reversal are not supported.

### Foreign exchange

Account currencies are fixed. The local rate provider supports:

| Currency pair | Rate (destination units per source unit) |
| --- | ---: |
| USD → SGD | 1.35000000 |
| SGD → USD | 0.74074074 |

The rate used and both currency amounts are saved on the transaction. The
destination amount is rounded to four decimal places using `HALF_EVEN`.
Unsupported currency pairs are rejected; no external rate service is called.

## API

All endpoints are served by the Spring Boot application, by default at
`http://localhost:8080`.

### `POST /transactions`

Posts a transfer. The request amount is in the source account's currency.
Provide an `Idempotency-Key` header.

```json
{
  "sourceAccountId": "00000000-0000-0000-0000-000000000001",
  "destinationAccountId": "00000000-0000-0000-0000-000000000002",
  "amount": "100.0000"
}
```

A new transfer returns `201 Created`; an idempotent replay returns `200 OK`.
The response includes the transaction ID, type, status, account IDs, source
and destination amounts/currencies, optional exchange rate, and timestamp.

Example FX request using the seeded USD and SGD accounts:

```powershell
curl.exe -i -X POST http://localhost:8080/transactions `
  -H "Content-Type: application/json" `
  -H "Idempotency-Key: example-fx-001" `
  -d '{"sourceAccountId":"00000000-0000-0000-0000-000000000003","destinationAccountId":"00000000-0000-0000-0000-000000000002","amount":"100.0000"}'
```

The deterministic USD→SGD rate produces source debit `-100.0000 USD` and
destination credit `135.0000 SGD`. The response includes
`"exchangeRate":"1.35000000"`; same-currency responses have
`"exchangeRate":null`.

### `POST /transactions/{transactionId}/reversals`

Posts a full reversal of an existing transfer. Provide an
`Idempotency-Key` header. A new reversal returns `201 Created`; a replay
returns `200 OK`.

Example:

```powershell
$transactionId = "replace-with-the-transaction-id-from-the-transfer-response"
curl.exe -i -X POST "http://localhost:8080/transactions/$transactionId/reversals" `
  -H "Idempotency-Key: example-reversal-001"
```

The response identifies the new `REVERSAL` transaction and its `reversalOf`
transaction ID. It retains the original transaction's account, amount,
currency, and FX-rate metadata; the actual reversal effect is expressed by
sign-inverted ledger entries.

### `GET /accounts/{id}`

Returns the account ID, currency, and current balance. An unknown account
returns `404 Not Found`.

```powershell
curl.exe http://localhost:8080/accounts/00000000-0000-0000-0000-000000000001
```

Example response:

```json
{
  "accountId": "00000000-0000-0000-0000-000000000001",
  "currency": "SGD",
  "balance": "1000.0000"
}
```

### `GET /accounts/{id}/transactions`

Returns an `items` array of ledger entries affecting the account, ordered by
transaction timestamp and transaction ID. Each item contains the transaction
ID and type, signed amount, account currency, timestamp, and (for a reversal)
the original transaction ID. The endpoint currently returns the full history
without pagination.

```powershell
curl.exe http://localhost:8080/accounts/00000000-0000-0000-0000-000000000001/transactions
```

### `GET /reconciliation`

Reconciles every account against its opening balance and ledger entries. The
response includes per-account results, separate currency totals, and
transaction checks. Discrepancies are returned as a successful `200 OK`
reconciliation response with overall status `DISCREPANCY`; the endpoint never
repairs data.

```powershell
curl.exe http://localhost:8080/reconciliation
```

To reconstruct the ledger through a UTC calendar date, use `asOfDate`:

```powershell
curl.exe "http://localhost:8080/reconciliation?asOfDate=2026-10-31"
```

An historical request includes entries before midnight UTC at the start of
the following day. Since historical balance snapshots are not stored,
historical account results contain reconstructed expected balances but omit
stored balances and differences. Invalid dates return `400 Bad Request` with
code `INVALID_AS_OF_DATE`.

Example response shape:

```json
{
  "asOfDate": null,
  "historicalReconstructionOnly": false,
  "status": "OK",
  "accountResults": [
    {
      "accountId": "00000000-0000-0000-0000-000000000001",
      "currency": "SGD",
      "openingBalance": "1000.0000",
      "ledgerEffect": "0.0000",
      "expectedBalance": "1000.0000",
      "storedBalance": "1000.0000",
      "difference": "0.0000",
      "status": "OK"
    }
  ],
  "currencyTotals": [
    {
      "currency": "SGD",
      "openingBalanceTotal": "1500.0000",
      "ledgerEffectTotal": "0.0000",
      "expectedBalanceTotal": "1500.0000",
      "storedBalanceTotal": "1500.0000",
      "difference": "0.0000",
      "status": "OK"
    },
    {
      "currency": "USD",
      "openingBalanceTotal": "1000.0000",
      "ledgerEffectTotal": "0.0000",
      "expectedBalanceTotal": "1000.0000",
      "storedBalanceTotal": "1000.0000",
      "difference": "0.0000",
      "status": "OK"
    }
  ],
  "transactionChecks": {
    "checked": 0,
    "discrepancies": []
  }
}
```

### Errors

Errors use Spring's RFC 9457 `ProblemDetail` response format with a stable
`code` property. Examples include `NOT_FOUND`, `INSUFFICIENT_FUNDS`,
`SELF_TRANSFER`, `IDEMPOTENCY_KEY_REUSED`, `DUPLICATE_REVERSAL`, and
`UNSUPPORTED_CURRENCY_PAIR`. An invalid reconciliation date returns
`INVALID_AS_OF_DATE`. Reconciliation discrepancies are reported in the
`200 OK` reconciliation response rather than as HTTP errors. Invalid requests
are rejected rather than silently rounded or posted.

## Run locally

Prerequisites:

- Docker with the Docker Compose plugin

From the repository root, build and start both the application and PostgreSQL:

```sh
docker compose up --build
```

To start both services in the background:

```sh
docker compose up --build -d
```

Compose waits for PostgreSQL to pass its health check before starting the
application. The database is published on port `5432`, and the application is
available at `http://localhost:8080`. Flyway creates the schema and seeds the
sample accounts on startup. The Compose file uses the fixed `ledger`/`ledger`
credentials for local development only; do not reuse them in shared or
deployed environments.

View service logs:

```sh
docker compose logs -f app
```

Stop the services:

```sh
docker compose down
```

Seeded sample accounts:

| Account ID | Currency | Opening balance |
| --- | --- | ---: |
| `00000000-0000-0000-0000-000000000001` | SGD | 1000.0000 |
| `00000000-0000-0000-0000-000000000002` | SGD | 500.0000 |
| `00000000-0000-0000-0000-000000000003` | USD | 1000.0000 |

Example request from PowerShell:

```powershell
curl.exe -i -X POST http://localhost:8080/transactions `
  -H "Content-Type: application/json" `
  -H "Idempotency-Key: example-transfer-001" `
  -d '{"sourceAccountId":"00000000-0000-0000-0000-000000000001","destinationAccountId":"00000000-0000-0000-0000-000000000002","amount":"100.0000"}'
```

The example transfers SGD 100 from account 1 to account 2.

## Run tests

```powershell
mvn verify
```

Integration tests start PostgreSQL 17 with Testcontainers and therefore require
a working Docker daemon. The tests cover account reads, transfer and FX
posting, history ordering, idempotent retries and key conflicts, validation,
rollback, concurrent debits/credits, reversal behavior, and reconciliation
including historical reconstruction and corrupted-data reporting.

## Assumptions and trade-offs

This implementation intentionally prioritizes correctness, auditability, and reliability over feature completeness. In a financial system, an incomplete feature set is preferable to incorrect balances, duplicated transfers, inconsistent ledger state, or behaviour that cannot be reliably explained and audited.

For that reason, the design focuses first on strong transactional guarantees, deterministic behaviour, immutable financial history, idempotency, and reconciliation. More production-oriented capabilities are intentionally left out where they would add complexity without improving the core correctness of the ledger.

The following assumptions and trade-offs define the boundaries of the exercise.

### Account and currency model

Each account is denominated in exactly one fixed three-letter currency code,
such as `SGD` or `USD`. An account does not hold multiple currencies or
sub-balances. Cross-currency transfers are movements between accounts with
different fixed currencies.

Amounts are represented with `BigDecimal` and persisted using fixed-precision
numeric columns. The exercise supports up to four fractional digits for
account balances and transaction amounts. This simplifies validation,
reconciliation rules, and balance handling while avoiding currency-specific
minor-unit complexity.

A production system may instead support currency-specific decimal precision,
multi-currency wallets, sub-ledgers per currency, or configurable currency
metadata.

### Opening balances

Each account stores an `opening_balance`. The ledger-derived expected balance
is:

```text
expected balance =
opening balance + sum of ledger entries
```

Opening balances are stored directly on the account for simplicity. In a
stricter production ledger, initial funding would normally be represented
through explicit ledger transactions and entries so that the ledger explains
the entire balance history from zero.

### Current balance as a projection

Each account stores a current `balance` for efficient reads. It is updated
atomically whenever a transaction posts. Ledger entries remain the immutable
audit trail describing how the account reached its current state.

These two representations should always agree:

```text
stored account balance
```

and:

```text
opening balance + ledger entries
```

The trade-off is faster balance reads at the cost of needing reconciliation to
detect divergence caused by bugs, manual database changes, or failed
invariants. The on-demand reconciliation endpoint detects and reports
divergence but does not repair it.

### Ledger entries are authoritative for financial effects

The financial effect of a transaction is represented by its signed ledger
entries. A negative entry is a debit, for example `-100.0000`; a positive
entry is a credit, for example `100.0000`.

Transaction metadata such as source account, destination account, source
amount, and exchange rate is retained for auditability and interpretation.
Account history and reversals use persisted ledger entries as the source of
truth for financial effects. This is especially important for reversals and
FX transactions, where recomputing amounts from metadata could introduce
rounding differences.

### Append-only transaction history

Posted transactions and ledger entries are immutable. A correction does not
edit or delete the original transaction; instead, a reversal creates a new
`REVERSAL` transaction with entries that are exact sign inverses of the
original entries.

```text
Original:
Account A   -100
Account B   +100

Reversal:
Account A   +100
Account B   -100
```

This preserves a complete audit trail showing both the original action and
its correction.

### Two-entry transaction model

Transfers currently produce exactly two ledger entries: a negative amount for
the source account and a positive amount for the destination account. This is
sufficient for the exercise.

A production ledger may require additional entries for fees, taxes,
commissions, settlement, FX clearing, suspense, or intermediary accounts.
These extensions are outside the current scope.

### Full reversals only

Only full reversals are supported; partial reversals are not modeled. If
reversing a transaction would cause an account to become negative, the
reversal is rejected. For example, reversing `A -> B 100` requires debiting
account B by 100. If B no longer has at least 100 available, the reversal
fails. This preserves the no-overdraft invariant.

A production system may instead support overdraft facilities, partial
reversals, manual adjustments, suspense accounts, or exception-handling
workflows.

### Idempotency

Transfer and reversal requests require an `Idempotency-Key`. The idempotency
hash is based on financially relevant client request fields. For a transfer,
these include the operation, source account, destination account, and source
amount. Server-derived values such as exchange rates, calculated destination
amounts, timestamps, and balances are not included.

As a result, retrying the same client request returns the originally posted
transaction rather than recalculating it. For example, if an FX transfer was
originally posted at `USD -> SGD = 1.35000000` and the configured rate later
changes, replaying the request with the same key still returns the original
transaction and rate.

The transfer key is claimed with PostgreSQL `INSERT ... ON CONFLICT DO
NOTHING` inside the same transaction as the posting. The unique database
constraint is the final guard against concurrent duplicate submissions. With
`READ_COMMITTED`, a losing request waits for the winner and then reads the
committed result using a fresh statement snapshot. If posting fails, the
claim rolls back too, so the key can be retried. Reusing a key with a
different request hash returns `409 Conflict`.

### Concurrency and locking

Account rows are locked pessimistically before balances are changed. All
affected accounts are locked in deterministic UUID order rather than
transaction direction. This avoids opposing lock orders for transfers such
as `A -> B` and `B -> A`, reducing deadlock risk.

Funds are checked only after all affected account locks have been acquired, so
concurrent transactions do not spend from stale balances.

The reconciliation endpoint is read-only, uses `READ_COMMITTED`, and does not
lock all accounts. Consequently, concurrent commits may occur between its
queries; its output is suitable for this small take-home but is not a
point-in-time database snapshot.

### Atomic posting

A transaction posts inside one database transaction. The idempotency claim,
transaction metadata, account balance updates, and ledger entries either all
commit or all roll back. This prevents partial posting, such as a source debit
without a destination credit, or transaction metadata without ledger entries.
The same atomicity rule applies to reversals.

### FX rate authority

The FX provider is deterministic and local for this exercise. The ledger
treats its provided rate as authoritative. The ledger applies the rate,
calculates the destination amount using the configured rounding rule, persists
the exact rate used, and preserves it for auditability and replay.

The ledger does not independently determine whether a rate was economically
optimal, current, or consistent with external market prices. Live market feeds,
quote expiry, spreads, fees, rate approvals, provider failover, price
validation, and market-data reconciliation are outside scope. A production
system could replace the deterministic provider with an external provider
behind the same abstraction.

### FX rounding

For a cross-currency transfer:

```text
destination amount =
source amount × exchange rate
```

The final destination amount is rounded to four decimal places using
`HALF_EVEN`. Intermediate values are not unnecessarily rounded. The persisted
destination amount and rate become part of the transaction record. Reversals
do not recalculate the FX conversion; they negate the original persisted
ledger entries exactly.

### FX value conservation

Different currencies are different units. A USD debit of 100 and SGD credit of
135 cannot be tested by adding `-100 + 135`; the amounts are not directly
additive. Instead, an FX transaction can be validated using its persisted
conversion relationship:

```text
135 SGD =
100 USD × 1.35 SGD/USD
```

The current model can verify that conversion was applied consistently
according to the stored rate. It does not prove that system-wide economic
value was preserved relative to an external market price.

### No FX clearing accounts

The exercise uses a simplified two-entry FX model:

```text
Customer USD   -100 USD
Customer SGD   +135 SGD
```

A more accounting-complete production model could introduce separate clearing
accounts:

```text
Customer USD      -100 USD
FX Clearing USD   +100 USD

FX Clearing SGD   -135 SGD
Customer SGD      +135 SGD
```

That would allow each currency to balance independently. FX clearing accounts
are intentionally omitted to keep the exercise focused on posting
correctness, idempotency, concurrency, and auditability.

### Currency totals

Balances should not be summed directly across different currencies; for
example, `1000 SGD + 500 USD` is not a meaningful single monetary total.
System totals are considered separately per currency:

```text
SGD total = sum of all SGD account balances
USD total = sum of all USD account balances
```

A single system-wide value requires a reporting currency and valuation
exchange rates, which is a reporting or valuation concern rather than a core
ledger concern.

### Reconciliation model

The designed reconciliation rules compare each account's stored balance with
its ledger-derived expected balance:

```text
expected balance =
opening balance + sum of ledger entries
```

Aggregate checks should be performed independently per currency. Same-currency
transfers should net to zero within that currency. FX transactions should be
checked against their stored conversion relationship rather than by summing
different currencies. Reversal entries should be exact sign inverses of the
original entries.

`GET /reconciliation` detects and reports discrepancies; it does not
automatically repair ledger state. Current requests compare stored and
ledger-derived balances. The endpoint checks same-currency transfer entry
counts and net effects, FX rates and converted destination amounts, and
reversal metadata and exact inverse entries. Currency totals are calculated
separately by currency; values in different currencies are never combined.
The response reports an overall `OK` or `DISCREPANCY` status, per-account
results, per-currency totals, and a transaction check count with discrepancy
codes and details. A discrepancy is a reconciliation result, not a failed
HTTP request.

### Historical reconstruction

Historical balances are reconstructed from opening balances plus ledger
entries before the start of the next UTC day for the selected `asOfDate`. The
system does not persist historical balance snapshots. Therefore, historical
results are ledger-derived reconstructions, not comparisons against stored
historical account balances.

A production system may persist daily closing balances, reconciliation runs,
month-end snapshots, or signed accounting periods.

The reconciliation service uses read-only `READ_COMMITTED` isolation and
does not lock accounts. A posting can commit between its separate read
queries, so results are not guaranteed to represent one database snapshot
during concurrent writes. This is a deliberate take-home trade-off; a larger
production reconciliation could use `REPEATABLE_READ`, database snapshots,
accounting cutoffs, and persisted month-end runs. Month-end reconciliation in
this project is an on-demand `asOfDate` query, not a scheduled or persisted
workflow.

### Scope boundary

The following concerns are outside the current exercise: partial reversals,
overdrafts, FX clearing accounts, fees and commissions, live FX feeds, rate
freshness, multi-currency accounts, accounting periods, persisted
reconciliation runs, authentication and authorization, pagination, scheduled
reconciliation, and settlement workflows.

These features could be added later without changing the core principles of
immutable ledger entries, atomic posting, deterministic locking, and
idempotent requests.

See [docs/design.md](docs/design.md) for the detailed data model, posting and
concurrency approach, idempotency rules, API behavior, and implementation
trade-offs.