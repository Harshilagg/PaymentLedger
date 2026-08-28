# Digital Wallet & Payment Ledger — Technical Spec

## Context

This is a portfolio/interview-showcase project: a fintech backend demonstrating correctness under concurrency in a double-entry ledger system, built on Java + Spring Boot. The goal isn't to launch a real payments product — it's to build something that survives hard interview questions about ACID transactions, idempotency, optimistic locking, and distributed-transaction correctness, with a test suite that actually proves those properties hold under real concurrent load.

The headline choices: Java + Spring Boot (validated as the right call — dominant stack at real fintechs, mature transactional tooling), double-entry bookkeeping (not a mutable single balance), and microservices from day one (wallet-service, ledger-service) using a saga + transactional outbox pattern rather than distributed 2PC — which is the part of this project doing the most interview-signal work, since it's the one every fintech backend role probes on. A companion theme running through the whole design: every place the system touches shared mutable state or crosses a service/transaction boundary has to be provably idempotent under Kafka's at-least-once delivery — this is treated as a first-class correctness requirement, not an edge case.

## Tech stack (final)

| Concern | Choice | Why |
|---|---|---|
| Language/framework | Java 21 + Spring Boot 3.x | Validated as correct — mature `@Transactional`, Spring Data JPA optimistic locking, huge fintech hiring signal |
| Database | PostgreSQL — **single instance, two schemas** (`wallet`, `ledger`), one dedicated DB user per schema with no cross-schema grants | See "Why one instance, not two" below |
| Messaging | Apache Kafka | Durable log + replay (rebuild read-models from history), standard event-driven fintech pattern, first-class Testcontainers support |
| Migrations | Flyway (separate migration history per schema) | Versioned schema history per service — fits the audit-trail ethos of the whole project |
| Testing | JUnit 5 + Mockito (unit) + Testcontainers (Postgres + Kafka, integration) + dedicated concurrency test suite | See Testing section |
| API docs | springdoc-openapi (Swagger UI) | Auto-generated from controller annotations, zero drift from actual code |
| Auth | Spring Security + JWT bearer tokens | Enough to demonstrate securing endpoints + ownership checks, without building a full IdP |
| Observability | Spring Boot Actuator + Micrometer | Health/metrics endpoints, standard expectation in any real Spring Boot service |
| Local dev | Docker Compose (Postgres ×1, Kafka, both services) | `docker-compose up` demos the whole system in one command |
| Money representation | `long` minor units in DB, `BigDecimal` at API boundary only | Structurally impossible to hit float rounding bugs |

### Why one instance, not two

Decided in favor of a single Postgres instance with two schemas and two least-privilege DB users (no cross-schema grants) over two separate instances. The isolation property that actually matters — no shared tables, no cross-service joins, independent migration history per service — is fully preserved by schema + role separation; a second instance would add a second connection pool, a second Flyway config, and a second Testcontainers container to every integration test for no additional correctness guarantee. Given this project's test suite is the centerpiece (concurrency tests, redelivery tests, saga integration tests all need to spin up fast and often), the two-container tax would make the suite slow enough that it stops getting run — a real cost for a hypothetical benefit. The only guarantee genuinely given up is instance-level failure isolation (one Postgres process going down takes both schemas with it), which is an acceptable v1 tradeoff and easy to name as a "here's what we'd change for production" answer in an interview.

## Architecture

Two services, each owning its own Postgres schema behind its own DB user (no shared tables, no cross-service joins, no cross-schema grants):

- **wallet-service** — the only client-facing API. Owns `Account`, `Wallet` (one currency each), and a materialized balance row per wallet (the optimistic-locking contention point). Validates requests, initiates transfers, exposes the public REST API + Swagger UI + JWT auth.
- **ledger-service** — owns the immutable, append-only double-entry journal (`LedgerEntry`). This is the actual source of truth. Never updates or deletes rows, only inserts. Internal-only (no public API — reached only via Kafka).

No API gateway — wallet-service is the sole client-facing API. There is no synchronous call path between the services anywhere: wallet-service and ledger-service communicate exclusively through the outbox + Kafka, for every transaction type (deposit, withdrawal, transfer). This keeps the failure/idempotency model uniform — every cross-service interaction is a saga step subject to at-least-once delivery, with no special-cased "fast path."

## Cross-service correctness: saga + transactional outbox

Every wallet-service transaction type (deposit, withdrawal, transfer) follows the same event-driven saga shape; transfer is the fullest version of it since it coordinates two wallets. No 2PC.

**Balance reservation (holds).** `Wallet` carries `balance_minor` (settled) and `reserved_minor` (held for pending debits); available balance is `balance_minor - reserved_minor`, computed, not stored. Any debit operation (withdrawal, outgoing transfer) reserves funds at initiation time, in the same local transaction that validates the request — so the check and the deduction from available balance are atomic, not two steps separated by a network hop. This is what makes the no-overdraft guarantee real: without it, two concurrent debits could both read a sufficient balance, both pass validation, and both post to the ledger before either is reflected back, driving the balance negative. Credits (deposits, incoming transfer legs) never need a hold — crediting can't overdraft.

**Transfer (source wallet is the debit leg):**

1. Client calls `POST /wallets/{id}/transfers` on wallet-service with an idempotency key.
2. wallet-service, in one local DB transaction: validates `balance_minor - reserved_minor >= amount` on the source wallet, then `reserved_minor += amount` (the hold) and bumps `@Version`, creates a `Transaction` record in `PENDING` state, and writes an outbox row (`transfer.initiated` event) — same transaction, atomic by construction. A concurrent debit against the same wallet either reads the now-reduced available balance and fails validation, or races on the same row update and gets an `OptimisticLockException`, retries, re-reads, and fails.
3. An outbox relay (polling publisher or Debezium-style CDC — spec recommends a simple scheduled poller for v1, documented as upgradeable to CDC later) publishes the event to Kafka.
4. ledger-service consumes `transfer.initiated` — deduplicated per the "Idempotent event consumption" section below — and in its own local DB transaction inserts the two balanced `LedgerEntry` rows (debit + credit, same `transactionId`), then writes its own outbox row (`transfer.posted`).
5. wallet-service consumes `transfer.posted`: **only if the `Transaction` is still `PENDING`** (see settlement idempotency below), it settles the source wallet (`reserved_minor -= amount; balance_minor -= amount`) and credits the destination wallet (`balance_minor += amount`), transitioning `Transaction` to `COMPLETED` in the same local transaction as the mutation.
6. If ledger-service rejects for a reason other than insufficient funds (that case is already prevented at step 2) — e.g. a frozen wallet, an internal error — it publishes `transfer.failed` instead. wallet-service consumes it and, **only if the `Transaction` is still `PENDING`**, releases the hold on the source wallet (`reserved_minor -= amount`, `balance_minor` untouched), posts a compensating reversal for whatever ledger entries did land, and transitions `Transaction` to `FAILED` with a reason, in the same local transaction as the mutation. Nothing is ever deleted or mutated in the ledger itself — it stays a complete, honest history including the failed attempt and its cleanup.

This state machine (`PENDING → COMPLETED` / `PENDING → COMPENSATING → FAILED`) is the centerpiece of the whole project and deserves its own diagram + tests.

**Deposit and Withdrawal** follow the identical shape, single-wallet: initiate (reserve, for withdrawal only) + outbox row in one local transaction → ledger-service posts entries → wallet-service settles on confirmation, guarded by the same `PENDING`-status check. Deposit skips the reservation step (nothing to protect against on a credit).

## Idempotent event consumption

Kafka is at-least-once — the outbox relay can publish an event and crash before marking its row published, and consumers must tolerate redelivery without corrupting state. Two distinct places need protection, with two different mechanisms because one is an insert and the other is a mutation:

**ledger-service (insert path).** A unique constraint on `LedgerEntry(transaction_id, wallet_id, direction)`, with insert-if-not-exists (`INSERT ... ON CONFLICT DO NOTHING`, or catch the constraint violation and treat it as a no-op). This encodes an actual domain invariant — a given transaction moves funds through a given wallet in a given direction exactly once — so it stays correct even if the application-level dedup check has a bug, without needing a separate `processed_events` tracking table. On a detected duplicate, ledger-service does not re-publish `transfer.posted` — the first successful processing already did.

**wallet-service (settlement path).** The unique constraint above doesn't help here — settlement is an `UPDATE`, not an `INSERT`, so a redelivered `transfer.posted` would double-settle: `reserved_minor` goes negative, `balance_minor` gets debited twice. Guarded instead by `Transaction.status`: the balance mutation is applied only if the transaction is still `PENDING`, and the status transition to `COMPLETED` happens in the same local transaction as the mutation — so a redelivered event finds the transaction already `COMPLETED` and is a no-op. The same guard protects the `transfer.failed` path: the hold is released only if the transaction is still `PENDING`, transitioning to `FAILED` atomically with the release, so a redelivered failure event can't release the same hold twice.

**Tests**: an integration test redelivers a `transfer.initiated` event and asserts exactly one pair of ledger rows exists for that `transaction_id`; a second integration test redelivers `transfer.posted` and asserts both wallets' balances moved exactly once.

## Optimistic locking, precisely

The ledger (`LedgerEntry` table) is insert-only — no lock needed, there's nothing to race on. The actual contention point is wallet-service's materialized `Wallet` row: reservation (initiation), settlement (confirmation), and hold-release (failure) all read-modify-write the same `balance_minor`/`reserved_minor`/`version` columns, and concurrent operations against the same wallet race to update that row. Spring Data JPA's `@Version` column handles this — the loser of the race gets an `OptimisticLockException`, and the service retries the update (re-reading the version, reapplying the delta) with a small bounded retry count before giving up and marking the transaction `FAILED`.

## Idempotency (client requests)

Every mutating endpoint (deposit, withdrawal, transfer) requires a client-supplied `Idempotency-Key` header. wallet-service stores `(key, requestHash, response, status, expiresAt)` in an `IdempotencyRecord` table (TTL ~24h). Contract:

- Same key + same request body hash → return the cached original response immediately, no re-execution.
- Same key + different request body hash → `409 Conflict` (this is a client bug, not a legitimate retry).
- New key → proceeds normally, record written in the same local transaction as the operation itself (atomic — no window where the operation succeeds but the idempotency record fails to save).

This is a distinct concern from "Idempotent event consumption" above: this guards the client-facing HTTP API against retried requests; that section guards internal Kafka consumers against redelivered events.

## Data model

**wallet-service** (schema `wallet`):

- `Account(id, owner_id, created_at)` — one per user.
- `Wallet(id, account_id, currency, status, balance_minor, reserved_minor, version, created_at)` — `balance_minor` is the settled balance, `reserved_minor` is funds held for pending debits, available balance is `balance_minor - reserved_minor` (computed). `version` is the `@Version` optimistic-lock column. One wallet per currency per account.
- `Transaction(id, type[DEPOSIT|WITHDRAWAL|TRANSFER|REVERSAL], from_wallet_id, to_wallet_id, amount_minor, currency, status[PENDING|COMPLETED|COMPENSATING|FAILED], idempotency_key, original_transaction_id, created_at, completed_at)` — `original_transaction_id` links a `REVERSAL` back to the transaction it reverses; null otherwise.
- `IdempotencyRecord(key, request_hash, response_body, status, created_at, expires_at)`.
- `OutboxEvent(id, aggregate_id, event_type, payload_json, published, created_at)`.
- `ExchangeRate(from_currency, to_currency, rate, effective_at)` — seeded static table for v1 FX (real-time rate feeds are out of scope).

**ledger-service** (schema `ledger`):

- `LedgerEntry(id, transaction_id, wallet_id, account_id, direction[DEBIT|CREDIT], amount_minor, currency, created_at)` — insert-only, unique constraint on `(transaction_id, wallet_id, direction)`. Every transaction produces at least 2 balanced rows (sum of debits == sum of credits per `transaction_id`); a cross-currency transfer produces 4 (debit source, credit an FX clearing account, debit the clearing account, credit destination) since a `LedgerEntry` never mixes currencies.

## Core flows

- **Deposit** — single-wallet credit, no reservation needed (credit-only, can't overdraft). Initiate (`Transaction PENDING` + outbox row, one local transaction) → ledger-service posts entries → wallet-service settles (`balance_minor += amount`) on confirmation, guarded by the `PENDING`-status check.
- **Withdrawal** — single-wallet debit, same shape as the transfer source leg: reserve at initiation (`reserved_minor += amount`, validated against available balance) → ledger-service posts entries → wallet-service settles (`reserved_minor -= amount; balance_minor -= amount`) on confirmation, or releases the hold on failure. Hard-blocked at zero, no overdraft, enforced by the reservation step rather than a plain pre-check.
- **Transfer** — the full saga described above. Same-currency: 2 ledger entries. Cross-currency: 4 entries via the FX clearing account, using the seeded `ExchangeRate` table.
- **Reversal** — never mutates or deletes the original entries. Posts a new `Transaction(type=REVERSAL)` whose ledger entries exactly offset the original (debit becomes credit and vice versa), linked via `original_transaction_id`.

## Error handling

- **Every error is RFC 7807 `ProblemDetail`** (`application/problem+json`), whether it comes from a controller, from `@RestControllerAdvice`, or from the security filter chain before any controller runs. `GlobalExceptionHandler` extends `ResponseEntityExceptionHandler` so Spring's own failures — validation above all — take the same shape; validation failures additionally carry a field-level `errors` map so a client can point at the offending input instead of parsing prose. `SecurityConfig` registers an `authenticationEntryPoint` and `accessDeniedHandler` because those two paths run *before* the advice exists and would otherwise emit their own default shapes.
- **"Doesn't exist" and "exists but isn't yours" both return 404**, with the same body and the same message. This is deliberate. A 404/403 split answers the question "is this a real id?" for ids the caller has no business knowing about, which is enough to enumerate valid accounts, wallets and transactions one probe at a time. The cost is that a legitimate user who mistypes their own wallet id gets "not found" rather than "not yours"; that is the right trade. `ResourceNotFoundException` is the single exception type behind both cases, so the two are indistinguishable by construction rather than by remembering to keep two branches in sync.
- **The `/error` dispatch is explicitly permitted** in the filter chain (`dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()`). Spring Boot renders errors by forwarding to `/error`, which re-enters the security filter chain as a fresh dispatch carrying no `SecurityContext`. Without that line the forward is judged an unauthenticated request to a protected path and its rejection *overwrites the real response* — which is why this service previously answered every 400, 403 and 404 with a bodyless 403, including for routes that match no controller at all. This is not a hole: an `ERROR` dispatch is container-internal and cannot be requested from outside.

## Rate limiting

- **Token bucket per authenticated user**, applied to write methods only. Reads are unlimited.
  Capacity is the burst a user may spend at once; refill rate is the sustained rate they settle back
  to. Both live in `application.yml`.
- **The decision is a Lua script executed inside Redis**, not Java. Redis runs a script atomically,
  so the read, the decision and the write happen without interleaving. A read-modify-write from the
  application would be three round trips, and two concurrent requests could both observe the same
  remaining count and both be allowed — letting a user exceed capacity precisely when they are
  trying hardest to, which is the case the limiter exists for.
- **Rejections are 429 with an RFC 7807 body**, written through the same `ProblemDetailSupport`
  helper as every other filter-chain error, plus `Retry-After` and
  `X-RateLimit-Limit`/`-Remaining`/`-Reset`. Those three headers are sent on **every** limited
  response, not only rejections, so a client can pace itself before being told no.
- **`/auth/**` is not rate limited.** It is unauthenticated, so there is no user to key a bucket on.
  Limiting it would need a different key and a different failure policy, and nothing here should be
  mistaken for protection against credential stuffing.
- **When Redis is unreachable the limiter fails open** — the request is allowed and a throttled WARN
  is logged. Every correctness guarantee in this system (no-overdraft, exactly-once posting, saga
  compensation) lives in Postgres and none depend on Redis, so the limiter is a protective control,
  not a correctness one. Failing closed would turn a cache outage into a total write outage, which
  is a worse incident than the abuse being prevented. The full argument, including the case against
  it, is on `RateLimiter#degrade`.

## Testing strategy

- **Unit tests** (JUnit 5 + Mockito): business logic in isolation — balance/reservation validation, saga step handlers, idempotency conflict detection, reversal entry generation — with repositories/Kafka producers mocked.
- **Integration tests** (Testcontainers: Postgres + Kafka): prove the full saga actually works against real infra — publish an event, assert the consumer posts the right ledger rows, assert the outbox relay actually delivers. Includes two redelivery tests: redeliver `transfer.initiated` and assert exactly one pair of ledger rows exists; redeliver `transfer.posted` and assert both wallets' balances moved exactly once.
- **Concurrency test suite** (the centerpiece test, worth the extra effort): fire N concurrent transfer/withdrawal requests at the same wallet via a thread pool / `CompletableFuture.allOf`. Polling balance from another thread to check "never went negative" is racy and rejected in favor of asserting the invariant at the source: the settlement/reservation code path itself asserts `balance_minor >= 0 && reserved_minor >= 0` immediately after every mutation, failing the test the instant a violation would occur rather than trying to catch it via an external observer. Once the run drains (all sagas resolved), post-run assertions check: the expected number of requests were rejected for insufficient funds; `sum(ledger entries for the wallet) == balance_minor`; and `reserved_minor == 0` (no dangling holds). This is the single test that proves the whole "ACID + optimistic locking + no overdraft" claim rather than just asserting it in a README.

## Non-goals (explicit, documented — not oversights)

- Fraud detection / AML / sanctions screening.
- ~~Rate limiting.~~ **No longer a non-goal** — a Redis token bucket now limits authenticated write
  endpoints. See "Rate limiting" below.
- Real-time FX rate feeds (static seeded table only).
- notification-service. A Kafka consumer that persists rows and exposes a GET endpoint repeats the consumer pattern already proven in ledger-service without introducing a new correctness problem. Scoped out to keep the surface area focused on transactional correctness.
- Building an actual identity provider (JWTs are issued by a minimal mock auth flow, not a full IdP).
- API gateway / service mesh.
- Kubernetes manifests (Docker Compose only for v1).

## Suggested build order

1. wallet-service core: Account/Wallet CRUD, JWT auth, Flyway migrations, Swagger.
2. Deposit (single-wallet, no reservation needed) end-to-end via outbox + Kafka, with idempotency.
3. ledger-service: LedgerEntry model with the `(transaction_id, wallet_id, direction)` unique constraint, insert-if-not-exists consumer, Flyway migrations.
4. Outbox relay wiring, using the deposit flow as the first end-to-end path (one-directional, no reservation, simplest saga).
5. Withdrawal — adds the reservation/hold pattern and the settlement-idempotency status guard.
6. Transfer saga (the hard part, two wallets) — happy path first, then failure/compensation path.
7. Optimistic-lock retry logic + the concurrency test suite (write this test while building the retry/reservation logic, not after — it should fail first and pass once the logic is right).
8. Reversal flow.
9. Cross-currency transfers via the FX clearing account.
10. docker-compose.yml wiring the full stack; Actuator/Micrometer.

## Decisions log (from interview)

1. Project purpose: portfolio/interview project.
2. Stack: Java + Spring Boot confirmed as the right call.
3. Ledger model: double-entry bookkeeping.
4. Service topology: microservices from day one (wallet-service, ledger-service).
5. Cross-service atomicity: saga + transactional outbox, async-only (no synchronous inter-service calls anywhere).
6. Idempotency contract (client requests): same key + same payload → cached response; same key + different payload → 409.
7. v1 transaction types: Deposit, Withdrawal, Transfer, Reversal. Hold/Capture is in v1 as the internal reservation mechanism underlying debit operations (withdrawal, outgoing transfer) — not a separately exposed API. This is what makes the no-overdraft guarantee hold under concurrency, not just in the single-request case.
8. Message broker: Kafka.
9. Optimistic lock target: the wallet's materialized `balance_minor`/`reserved_minor` row (ledger entries are insert-only, no lock needed there).
10. Money representation: integer minor units in DB, BigDecimal at the API boundary.
11. Saga failure handling: compensating reversal entries plus hold release, transaction ends in FAILED. Both guarded against duplicate application via the `Transaction.status` check (redelivery safety).
12. Currency model: single currency per wallet, multiple wallets per account for multi-currency.
13. Overdraft policy: hard-blocked at zero, no overdraft — enforced by atomic reservation at initiation, not a pre-check that can race.
14. Auth: JWT bearer tokens via Spring Security.
15. Testing depth: unit (Mockito) + integration (Testcontainers: Postgres + Kafka), including consumer-redelivery tests.
16. Concurrency test suite: in scope for v1; asserts invariants at the mutation site (never negative) plus post-run reconciliation (ledger sum == balance, holds fully drained), not just a final-balance check.
17. FX rate source: fixed/seeded rate table, no live feed.
18. Fraud/AML/rate-limiting: explicitly out of scope for v1.
19. API gateway: none — wallet-service is the sole client-facing API.
20. Local dev: Docker Compose runs the entire stack.
21. Database topology: single Postgres instance, two schemas, two least-privilege DB users, no cross-schema grants — chosen over two instances to keep the Testcontainers-heavy test suite fast, at the cost of instance-level failure isolation (acceptable for v1).
22. Migrations/observability: Flyway (per-schema history) + Actuator/Micrometer, standard defaults.
