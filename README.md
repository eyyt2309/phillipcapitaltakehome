# Internal Ledger System

A Java/Spring Boot REST API for an internal financial ledger. It supports
atomic account transfers, USD/SGD transfers, idempotent requests, immutable
full reversals, account history, and read-only reconciliation.

## Quick start

Prerequisite: Docker with the Docker Compose plugin.

From the repository root, build and start the app and PostgreSQL:

```sh
docker compose up --build
```

The API is available at `http://localhost:8080`; PostgreSQL is published on
port `5432`. Compose waits for the database health check before starting the
app. Flyway initializes the schema and seeds the development accounts.

```sh
docker compose logs -f app  # follow application logs
docker compose down         # stop services
```

The Compose configuration uses fixed `ledger` / `ledger` credentials for local
development only. Do not reuse them in shared or deployed environments.

Seeded accounts:

| Account ID | Currency | Opening balance |
| --- | --- | ---: |
| `00000000-0000-0000-0000-000000000001` | SGD | 1000.0000 |
| `00000000-0000-0000-0000-000000000002` | SGD | 500.0000 |
| `00000000-0000-0000-0000-000000000003` | USD | 1000.0000 |

## Architecture summary

```text
HTTP client → Spring MVC → application services → PostgreSQL
                                  ↑                  ↑
                         Spring Data JPA          Flyway
```

The database has three core tables: `accounts` stores opening and current
balances; `ledger_transactions` stores immutable transfer and reversal
metadata; `ledger_entries` records signed account effects (debits are
negative, credits positive). Monetary values use `BigDecimal` in the
application and fixed-precision PostgreSQL `NUMERIC` columns.

Posting is atomic. Transfers and reversals lock affected accounts in UUID
order and check funds after locking. Idempotency claims and ledger posting
share the same database transaction. Reconciliation inspects ledger and
balance state without repairing it.

## API examples

All examples use PowerShell `curl.exe`. Transfer amounts are in the source
account currency; every transfer and reversal requires an `Idempotency-Key`.

### Transfer

```powershell
curl.exe -i -X POST http://localhost:8080/transactions `
  -H "Content-Type: application/json" `
  -H "Idempotency-Key: transfer-001" `
  -d '{"sourceAccountId":"00000000-0000-0000-0000-000000000001","destinationAccountId":"00000000-0000-0000-0000-000000000002","amount":"100.0000"}'
```

New requests return `201 Created`; matching retries return the original
transaction with `200 OK`. Reusing a key for different request details returns
`409 Conflict`.

The local FX provider supports USD→SGD at `1.35000000` and SGD→USD at
`0.74074074`. For example, transferring `100.0000 USD` to the seeded SGD
account credits `135.0000 SGD`.

### Account balance and transaction history

```powershell
curl.exe http://localhost:8080/accounts/00000000-0000-0000-0000-000000000001
curl.exe http://localhost:8080/accounts/00000000-0000-0000-0000-000000000001/transactions
```

### Reverse a transfer

Use the transaction ID returned by the transfer:

```powershell
$transactionId = "<transaction-id>"
curl.exe -i -X POST "http://localhost:8080/transactions/$transactionId/reversals" `
  -H "Idempotency-Key: reversal-001"
```

Reversals are full, new transactions with exact sign-inverted original entries.

### Reconcile

```powershell
curl.exe http://localhost:8080/reconciliation
curl.exe "http://localhost:8080/reconciliation?asOfDate=2026-10-31"
```

The read-only endpoint returns `OK` or `DISCREPANCY` with account results,
separate totals per currency, and transaction checks. Historical results
reconstruct balances through the specified UTC date; no historical balance
snapshots are stored.

## Tests

Run the full Maven verification suite:

```sh
mvn verify
```

This requires JDK 21, Maven 3.9 or newer, and a working Docker daemon for the
PostgreSQL 17 Testcontainers integration tests.

## Assumptions and trade-offs

- Accounts have one fixed currency; only configured USD/SGD FX pairs are
  supported. FX rates are deterministic local values, not live market quotes.
- Destination FX amounts are rounded to four decimal places using
  `HALF_EVEN`. The exercise uses two ledger entries for FX and does not model
  clearing accounts or cross-currency value totals.
- Only full reversals are supported. Transaction and ledger history is
  append-only by application behavior.
- Reconciliation is on-demand and read-only. Historical balances are
  reconstructed, not compared with snapshots. `READ_COMMITTED` means results
  are not guaranteed to represent a point-in-time snapshot during concurrent
  posting.
- Authentication, authorization, pagination, fees, and scheduled or
  persisted reconciliation are outside this take-home's scope.

For the detailed data model, transaction flow, concurrency strategy, and
reconciliation design, see [`docs/design.md`](docs/design.md).

For deeper rationale behind the major engineering decisions and production
trade-offs, see [`docs/design-decisions.md`](docs/design-decisions.md).
