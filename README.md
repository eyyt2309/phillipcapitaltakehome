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

- An account has one fixed three-letter currency and supports at most four
  fractional digits.
- Opening balances are stored on the account for this exercise. In a
  production ledger, initial funding would normally be represented by ledger
  entries.
- Transfers and reversals are append-only, each with two signed ledger entries.
- Full reversals only; if the inverse entries would overdraw an account, the
  reversal is rejected.
- The FX provider is deterministic and local. Rate sourcing, freshness, fees,
  and FX clearing accounts are outside the exercise's scope.
- Balances are a projection for reads; the entries are the transaction audit
  trail. Reconciliation is a planned feature, not currently available through
  the API.

See [docs/design.md](docs/design.md) for the detailed data model, posting and
concurrency approach, idempotency rules, API behavior, and implementation
trade-offs.