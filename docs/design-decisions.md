# Design Decisions and Rationale

This project intentionally prioritizes **correctness, auditability, and reliability over feature completeness**.

In a financial system, incorrect balances, duplicate transfers, inconsistent ledger state, or unexplained mutations are more serious than missing secondary features. For that reason, the implementation focuses on strong transactional guarantees, deterministic behavior, immutable history, idempotency, concurrency safety, and reconciliation.

The sections below explain the major design decisions and trade-offs.

## Append-only ledger

Posted financial history is immutable.

Once a transaction is committed, the system does not edit or delete it. Corrections are represented through new reversal transactions.

For example:

```text
Original:
Account A   -100
Account B   +100

Reversal:
Account A   +100
Account B   -100
```

This preserves a complete audit trail and makes historical activity explainable.

The trade-off is that more rows are created over time, but this is preferable to silently mutating financial history.

## Separate transactions and ledger entries

`ledger_transactions` stores business-level metadata about the transaction.

`ledger_entries` stores the actual financial effect on accounts.

For example:

```text
Transaction:
A -> B, 100 SGD

Entries:
A  -100
B  +100
```

This separation allows transaction metadata to remain descriptive while ledger entries remain authoritative for balance movement.

It also makes the model easier to extend later for features such as fees, clearing accounts, taxes, or more complex multi-entry transactions.

## Stored account balance

Each account stores its current balance directly.

This allows:

```http
GET /accounts/{id}
```

to return the balance without calculating the sum of the full ledger on every request.

The trade-off is that there are now two representations of state:

```text
stored account balance
```

and:

```text
opening balance + ledger entries
```

These should always agree.

Reconciliation exists to independently verify that they remain consistent.

## Opening balance

Each account stores an `opening_balance`.

For reconciliation:

```text
expected balance =
opening balance + sum of ledger entries
```

This is a simplification for the exercise.

In a stricter production ledger, initial funding would typically be represented as explicit ledger transactions so that every unit of value is explained through entries starting from zero.

The current design keeps initialization simple while still enabling deterministic reconciliation.

## BigDecimal for monetary values

All monetary arithmetic uses `BigDecimal`.

Floating-point types such as `double` are avoided because many decimal values cannot be represented exactly in binary floating-point.

The database uses fixed-precision numeric columns for the same reason.

This helps ensure deterministic monetary calculations.

## Four-decimal amount precision

Transaction amounts and account balances support up to four fractional digits.

This is an exercise-level simplification.

A production financial system may instead define precision per currency, because different currencies have different minor-unit rules.

## Pessimistic account locking

Account rows are pessimistically locked before balances are checked and modified.

This protects against concurrent overspending.

For example:

```text
Balance = 100

Transaction 1 wants 80
Transaction 2 wants 80
```

Without locking, both could read the same balance and both succeed.

With locking, one transaction completes first and the second evaluates the updated balance.

## Deterministic UUID lock ordering

When multiple accounts must be locked, they are locked in ascending UUID order.

The ordering does not depend on transfer direction.

Without deterministic ordering:

```text
Transaction 1:
A -> B
locks A then B

Transaction 2:
B -> A
locks B then A
```

could create a deadlock.

Using one global ordering significantly reduces this risk.

## Funds checked only after locks are acquired

Sufficient funds are checked only after all affected account locks are held.

A balance checked before locking could become stale before the transaction is posted.

The intended sequence is:

```text
lock accounts
read current balances
check funds
apply updates
```

This ensures financial decisions are made from serialized account state.

## Atomic database transactions

Posting happens inside one database transaction.

The following operations commit or roll back together:

```text
idempotency claim
transaction row
account balance updates
ledger entries
```

This prevents partial outcomes such as:

```text
source debited
destination not credited
```

or:

```text
transaction created
ledger entries missing
```

The same principle applies to reversals.

## Idempotency keys

Financial API calls may be retried because of network timeouts or uncertain client responses.

Without idempotency, a retry could duplicate a transfer.

The system therefore requires an `Idempotency-Key`.

A successful retry returns the original transaction instead of posting again.

## Request hash

An idempotency key alone is not enough because a caller could accidentally reuse the same key for a different request.

The system stores both:

```text
idempotency key
request hash
```

Behavior is:

```text
same key + same request hash
-> replay original response

same key + different request hash
-> reject with conflict
```

The stored hash is therefore a fingerprint of the original logical request.

## Canonical request normalization

Financially equivalent request values are normalized before hashing.

For example:

```text
100
100.0
100.0000
```

should represent the same logical amount.

Normalization prevents equivalent requests from producing different idempotency fingerprints.

## Database-backed idempotency race protection

Idempotency uses a unique database constraint together with PostgreSQL:

```sql
INSERT ... ON CONFLICT DO NOTHING
```

This avoids race conditions from a simple application-level check-then-insert flow.

The database remains the final authority for uniqueness under concurrency.

## READ_COMMITTED isolation

The system uses PostgreSQL `READ_COMMITTED`.

This is sufficient because posting correctness is enforced through:

- explicit row locks
- unique constraints
- database transactions
- atomic updates

Stronger isolation levels would introduce additional complexity without being necessary for the current posting model.

## Immutable reversals

A reversal does not edit the original transaction.

It creates a new `REVERSAL` transaction.

This ensures the system can always show:

```text
what originally happened
what correction was later made
```

rather than hiding the original activity.

## Reversal entries are derived from persisted ledger entries

Reversal amounts are not recalculated from transaction metadata.

Instead, each original ledger entry is negated exactly.

For example:

```text
Original:
-100.0000
+135.0000

Reversal:
+100.0000
-135.0000
```

This avoids recomputation differences and is especially important for FX transactions where rounding may have occurred.

## Full reversals only

Only complete reversals are supported.

Partial reversals are intentionally left out.

Partial reversals would require additional rules around remaining amounts, repeated corrections, and business semantics that are beyond the scope of the exercise.

## Reversal overdraft protection

A reversal may require debiting the original recipient.

If that account no longer has sufficient funds, the reversal is rejected.

Reversals do not bypass the invariant that customer account balances cannot become negative.

A production system could support overdraft accounts or manual exception workflows, but those are outside the current scope.

## Deterministic local FX provider

FX rates are provided by a deterministic local implementation.

This allows the project to demonstrate:

- cross-currency posting
- conversion calculations
- rate persistence
- FX reversals
- reconciliation

without introducing unrelated infrastructure such as:

- external market APIs
- network failures
- caching
- rate expiry
- provider failover
- market-data reconciliation

A production implementation could replace the provider behind the same interface.

## Persisting the exchange rate

The exact rate used for an FX transaction is stored permanently.

For example:

```text
100 USD
rate = 1.35000000
destination = 135 SGD
```

must remain explainable even if market rates change later.

Historical transactions therefore never depend on a future rate lookup.

## HALF_EVEN FX rounding

The destination amount is calculated using:

```text
source amount × exchange rate
```

and rounded to four decimal places using `HALF_EVEN`.

A single deterministic rounding rule avoids inconsistent outcomes.

Intermediate values are not unnecessarily rounded.

## FX rate excluded from idempotency hash

The exchange rate is server-derived rather than supplied by the client.

Therefore it is not included in the idempotency fingerprint.

This ensures that replaying the same client request returns the original posted transaction even if the configured FX rate later changes.

## Currencies are never directly summed

Amounts in different currencies are treated as different units.

For example:

```text
100 USD + 500 SGD
```

does not represent a meaningful monetary total.

Reconciliation therefore aggregates balances separately by currency.

A single total would require an explicit reporting currency and valuation policy, which is outside the core ledger scope.

## Simplified FX entry model

FX currently uses two ledger entries:

```text
USD account  -100 USD
SGD account  +135 SGD
```

These entries are not expected to numerically net to zero because they are in different currencies.

A more accounting-complete production system could introduce FX clearing accounts:

```text
Customer USD      -100 USD
FX Clearing USD   +100 USD

FX Clearing SGD   -135 SGD
Customer SGD      +135 SGD
```

The current design intentionally omits this extra infrastructure.

## Reconciliation

Reconciliation independently verifies that stored state matches ledger-derived state.

The core equation is:

```text
stored balance =
opening balance + ledger effects
```

Reconciliation also verifies:

- same-currency transaction conservation
- FX amount and rate relationships
- expected ledger entry structure
- reversal inverse relationships
- per-currency aggregate consistency

## Reconciliation reports but does not repair

Reconciliation is read-only.

If a discrepancy is detected, it is reported but never automatically fixed.

Automatic repair could hide the cause of financial corruption and weaken auditability.

Investigation and correction should be explicit.

## HTTP 200 for reconciliation discrepancies

A reconciliation request that successfully detects inconsistent accounting data still returns HTTP `200`.

The API operation itself succeeded.

The returned business status indicates:

```text
OK
```

or:

```text
DISCREPANCY
```

A discrepancy is therefore treated as reconciliation output rather than an HTTP server failure.

## Historical reconciliation

Historical balances are reconstructed using:

```text
opening balance
+
ledger entries before the requested cutoff
```

The system does not currently persist historical account balance snapshots.

Therefore historical reconciliation does not compare reconstructed past balances against today's stored balance.

Doing so would incorrectly create false discrepancies.

## UTC historical cutoffs

`asOfDate` is interpreted using a UTC day boundary.

For example:

```text
asOfDate = 2026-10-31
```

means entries before:

```text
2026-11-01T00:00:00Z
```

are included.

This provides one explicit and deterministic interpretation of date boundaries.

## Read-only reconciliation isolation

Reconciliation runs as a read-only operation under `READ_COMMITTED`.

It does not pessimistically lock every account or block transaction posting.

The trade-off is that a transaction could commit between separate reconciliation queries.

A larger production system could instead use:

- `REPEATABLE_READ`
- database snapshots
- accounting cutoffs
- persisted reconciliation runs

Those mechanisms are intentionally outside this exercise.

## PostgreSQL

PostgreSQL was chosen because the design relies heavily on:

- ACID transactions
- row-level locking
- uniqueness constraints
- fixed-precision numeric types
- conflict-aware inserts
- strong relational integrity

These properties fit financial ledger workloads well.

## Flyway

Flyway manages database schema changes.

Schema migrations are version-controlled and run deterministically.

This makes database setup reproducible for both development and review.

## Testcontainers

Integration tests use a real PostgreSQL instance through Testcontainers.

This is important because many core behaviors cannot be proven reliably using mocks or an in-memory substitute.

Examples include:

- pessimistic row locking
- transaction isolation
- unique-constraint races
- `ON CONFLICT`
- rollback behavior
- database constraints

## Docker Compose

Docker Compose is used to make local setup simple and repeatable.

The application and database can be started together with:

```bash
docker compose up --build
```

The backend waits for the PostgreSQL health check before starting.

This reduces reviewer setup effort and environment differences.

## Database constraints in addition to service validation

Business validation exists in the service layer for clear domain errors.

Database constraints still provide final integrity protection.

This is deliberate defense in depth.

If application code contains a bug or a future code path bypasses validation, the database still rejects invalid state.

## Layered Spring Boot architecture

The application uses a straightforward layered structure:

```text
Controller
   ↓
Service
   ↓
Repository
   ↓
PostgreSQL
```

Controllers handle HTTP concerns.

Services contain business rules and transaction boundaries.

Repositories handle persistence.

This keeps responsibilities clear without introducing unnecessary framework complexity.

## Modular monolith instead of microservices

The system is intentionally implemented as one service.

Financial transfers benefit from simple local transaction boundaries.

Splitting the ledger into multiple services would make atomic posting significantly harder while providing little benefit for the size of this exercise.

## No event-sourcing framework

The ledger is append-oriented, but the project does not implement a full event-sourcing architecture.

The current design already provides many useful properties:

- immutable transaction history
- reconstructable account state
- auditability
- explicit corrections

without adding an event store, projection framework, or messaging infrastructure.

## Authentication and authorization omitted

Authentication and authorization are important production requirements but were not central to the ledger correctness problem.

The implementation complexity was instead spent on:

- atomicity
- concurrency
- idempotency
- reversals
- FX
- reconciliation

## Pagination omitted

Transaction history does not currently implement pagination.

For the small exercise dataset, this keeps the API simple.

A production implementation should use pagination or cursor-based retrieval for large histories.

## Scheduled reconciliation omitted

Reconciliation is available on demand, including historical `asOfDate` reconstruction.

The project does not persist reconciliation runs or schedule month-end jobs.

A production system could add:

- scheduled reconciliation
- persisted run results
- accounting-period snapshots
- month-end close workflows

without changing the core ledger model.

## Summary

The overall design philosophy is:

```text
Prioritize:
- correctness
- atomicity
- auditability
- reliability
- idempotency
- concurrency safety
- reconciliation

Simplify:
- external FX infrastructure
- clearing accounts
- partial reversals
- multi-currency wallets
- authentication
- pagination
- scheduled reporting
- production-scale operational tooling
```

The goal is to ensure that the implemented financial behavior is correct and explainable before expanding the feature set.