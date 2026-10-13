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

**Reconciliation status:** the assignment and [design](docs/design.md) call for
current/month-end reconciliation, but the current application does not yet
implement a reconciliation endpoint or reconciliation tests. The design
describes the intended on-demand checks; this README does not represent them as
available functionality.

## Technology

- Java 21
- Spring Boot 3.5
- Spring Web, Spring Data JPA, and Bean Validation
- PostgreSQL 17
- Flyway database migrations
- JUnit 5, Spring Boot Test, and Testcontainers
- Docker Compose for local PostgreSQL

## How it works

The application follows a controller → service → repository → PostgreSQL
structure. Controllers handle HTTP input and responses, while
`TransactionService` applies transfer and reversal rules inside database
transactions.

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

### `POST /transactions/{transactionId}/reversals`

Posts a full reversal of an existing transfer. Provide an
`Idempotency-Key` header. A new reversal returns `201 Created`; a replay
returns `200 OK`.

### `GET /accounts/{id}`

Returns the account ID, currency, and current balance. An unknown account
returns `404 Not Found`.

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

### Errors

Errors use Spring's RFC 9457 `ProblemDetail` response format with a stable
`code` property. Examples include `NOT_FOUND`, `INSUFFICIENT_FUNDS`,
`SELF_TRANSFER`, `IDEMPOTENCY_KEY_REUSED`, `DUPLICATE_REVERSAL`, and
`UNSUPPORTED_CURRENCY_PAIR`. Invalid requests are rejected rather than
silently rounded or posted.

## Run locally

Prerequisites:

- JDK 21
- Maven 3.9 or newer
- Docker with the Docker Compose plugin

From the repository root, start PostgreSQL:

```powershell
docker compose up -d postgres
```

The Compose service creates a `ledger` database with the development defaults
`ledger` / `ledger_dev_password` and publishes PostgreSQL on port `5432`.
These credentials are for local development only. Override them before using
the application in any shared or deployed environment.

Start the application:

```powershell
mvn spring-boot:run
```

Flyway creates the schema and seeds these sample accounts:

| Account ID | Currency | Opening balance |
| --- | --- | ---: |
| `00000000-0000-0000-0000-000000000001` | SGD | 1000.0000 |
| `00000000-0000-0000-0000-000000000002` | SGD | 500.0000 |
| `00000000-0000-0000-0000-000000000003` | USD | 1000.0000 |

The connection settings can be overridden with `DB_HOST`, `DB_PORT`,
`DB_NAME`, `DB_USER`, and `DB_PASSWORD`.

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
mvn test
```

Integration tests start PostgreSQL 17 with Testcontainers and therefore require
a working Docker daemon. The tests cover account reads, transfer and FX
posting, history ordering, idempotent retries and key conflicts, validation,
rollback, concurrent debits/credits, and reversal behavior.

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
invariants. Reconciliation is part of the design, but is not currently exposed
by the application API.

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

### Concurrency and locking

Account rows are locked pessimistically before balances are changed. All
affected accounts are locked in deterministic UUID order rather than
transaction direction. This avoids opposing lock orders for transfers such
as `A -> B` and `B -> A`, reducing deadlock risk.

Funds are checked only after all affected account locks have been acquired, so
concurrent transactions do not spend from stale balances.

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

Reconciliation is intended to detect and report discrepancies, not
automatically repair ledger state. It is not currently implemented as an API
endpoint.

### Historical reconstruction

Historical balances can be reconstructed from an opening balance plus ledger
entries through a selected cutoff. The system does not currently persist
historical balance snapshots. Therefore, an `asOfDate` reconciliation would
represent a ledger-derived historical reconstruction, not a comparison
against a separately stored historical account balance.

A production system may persist daily closing balances, reconciliation runs,
month-end snapshots, or signed accounting periods.

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