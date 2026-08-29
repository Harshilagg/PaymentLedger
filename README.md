# Digital Wallet & Payment Ledger

A double-entry payment ledger backend split across two Spring Boot services — `wallet-service` and `ledger-service` — coordinated through a saga + transactional outbox over Kafka, with no distributed transaction anywhere in the system.

See [SPEC.md](SPEC.md) for the full design: architecture, the balance-reservation model that makes the no-overdraft guarantee hold under concurrency, the idempotency story (both client-facing and Kafka-consumer-facing), error handling, rate limiting, and the test strategy.

## Status

Deposit, withdrawal, transfer (same- and cross-currency) and reversal all work end-to-end. The
transcript in [Quick smoke test](#quick-smoke-test) below was executed against the running stack.

What is built:

- **Authentication** — `POST /auth/register`, `/auth/login`, `/auth/refresh`. Access tokens live 15
  minutes, refresh tokens 14 days. Refresh tokens **rotate on every use**, and presenting an
  already-revoked one revokes every refresh token for that user, on the assumption that a token
  arriving twice was stolen rather than replayed by accident. Only the SHA-256 digest of a refresh
  token is stored, never the raw value.
- **Rate limiting** — a Redis token bucket per authenticated user on write methods, evaluated by a
  Lua script so the check, decrement and expiry are atomic. Defaults: capacity 60, refill 10/sec.
  Reads are unlimited. Fails open if Redis is unreachable.
- **Exchange rate caching** — rate lookups cached in Redis keyed on the currency pair, TTL 10
  minutes, with hit/miss counts logged. Absence is deliberately not cached. **Measured effect: none
  — see [Performance](#performance).**
- **Ledger read endpoint** — `GET /transactions/{id}/ledger-entries` exposes the underlying
  double-entry rows, served by wallet-service calling ledger-service over HTTP.
- **Pagination** — `GET /wallets/{walletId}/transactions` returns a Spring `Page`, default 20 per
  page sorted by `createdAt` descending.
- **RFC 7807 errors** — every error is `application/problem+json`, including the ones raised inside
  the security filter chain. A missing resource and someone else's resource both return 404 with
  identical bodies, deliberately, so status codes cannot be used to enumerate valid ids.

### Tests

| | Unit (`*Test.java`) | Integration (`*IT.java`) |
|---|---:|---:|
| wallet-service | 84 | 22 |
| ledger-service | 15 | 1 |

Unit tests run anywhere with `mvn test`. **All 23 integration tests require a reachable Docker
daemon** — they use Testcontainers for Postgres, Kafka and Redis — and run under Failsafe on
`mvn verify`. They execute on every push via [`.github/workflows/test.yml`](.github/workflows/test.yml),
which runs `mvn -B verify` for both services on Java 21. The counts above are taken from a passing
CI run.

## Running the whole stack

```
docker compose up -d --build
```

This builds and starts:

| Service | Host access |
|---|---|
| postgres | published on `5432` |
| kafka | published on `9092` |
| wallet-service | published on `8081` |
| ledger-service | **`expose` only** — reachable inside the compose network, not from the host |
| redis | **`expose` only** — same |

`ledger-service` and `redis` are deliberately unpublished: the ledger read endpoint performs no
authorization of its own, and Redis holds rate-limit buckets and cached rates with no authentication.
The compose network is their boundary.

First boot is slower (Maven dependency resolution inside the build, Kafka's internal topics
settling); `docker compose logs -f wallet-service` shows `Started WalletServiceApplication` when
it's ready.

Swagger UI for the client-facing API: `http://localhost:8081/swagger-ui.html`

## Quick smoke test

Requires `jq`. **A deposit returns `202 Accepted` and settles asynchronously through the saga**, so
the balance is still `0.0` the instant it returns — the poll in step 5 is not optional, and skipping
it is the most likely reason to think this project is broken when it isn't.

```bash
# 1. Register (or POST the same body to /auth/login if the user already exists)
TOKEN=$(curl -s -X POST http://localhost:8081/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"demo@example.com","password":"demo-password"}' | jq -r .accessToken)

# 2. Create an account
ACCOUNT_ID=$(curl -s -X POST http://localhost:8081/accounts \
  -H "Authorization: Bearer $TOKEN" | jq -r .id)

# 3. Create a USD wallet
WALLET_ID=$(curl -s -X POST http://localhost:8081/accounts/$ACCOUNT_ID/wallets \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"currency":"USD"}' | jq -r .id)

# 4. Deposit 100.00 -- returns 202 with status PENDING
curl -s -X POST http://localhost:8081/wallets/$WALLET_ID/deposits \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-1" -d '{"amount":100.00}'

# 5. Poll until the saga settles. The transaction list is paginated, hence .content[0]
TX_ID=$(curl -s "http://localhost:8081/wallets/$WALLET_ID/transactions" \
  -H "Authorization: Bearer $TOKEN" | jq -r '.content[0].transactionId')

until [ "$(curl -s http://localhost:8081/transactions/$TX_ID \
  -H "Authorization: Bearer $TOKEN" | jq -r .status)" = "COMPLETED" ]; do
  echo "  still settling..."; sleep 1
done

# 6. Balance after settlement
curl -s http://localhost:8081/wallets/$WALLET_ID -H "Authorization: Bearer $TOKEN"

# 7. The double-entry rows behind that transaction
curl -s http://localhost:8081/transactions/$TX_ID/ledger-entries \
  -H "Authorization: Bearer $TOKEN"
```

Reading the balance immediately after step 4 and again after step 5 shows the asynchrony directly:

```json
// immediately after the 202
{"balance": 0.0, "reserved": 0.0, "available": 0.0, ...}

// after the poll completes
{"balance": 100.0, "reserved": 0.0, "available": 100.0, ...}
```

On the hardware in [docs/PERFORMANCE.md](docs/PERFORMANCE.md) the poll printed `still settling...`
seven times before the deposit completed.

Step 7 returns the balanced pair — a `CREDIT` to your wallet and an offsetting `DEBIT` from the
external clearing account:

```json
[
  {"walletId": "775401f6-...", "direction": "CREDIT", "amount": 100.0, "currency": "USD", ...},
  {"walletId": "f5f58488-...", "direction": "DEBIT",  "amount": 100.0, "currency": "USD", ...}
]
```

## What this demonstrates

- **Saga + transactional outbox over Kafka** — no distributed transaction anywhere; each service
  commits locally and events cross the boundary through an outbox table drained by a relay.
- **No overdraft under concurrency** — balances are reserved before settlement against a row guarded
  by an `@Version` column, with bounded retry on conflict (`OptimisticLockRetrier`, 5 attempts) and
  a 503 when those are exhausted.
- **Idempotency on both faces** — an `Idempotency-Key` header contract for clients on all four
  mutating endpoints, and redelivery dedupe on the Kafka consumers so at-least-once delivery cannot
  post an entry twice.
- **Redis token-bucket rate limiting** — atomic via a server-side Lua script, because a
  read-modify-write from the application would let concurrent requests exceed the bucket; fails open
  if Redis is unavailable, since a rate limiter outage should not become a payments outage.
- **Redis exchange rate caching** — with the measured outcome reported honestly: a 99.96% hit rate
  and no detectable latency benefit, because the table it caches was never the bottleneck.

## Performance

Full method, numbers and caveats: **[docs/PERFORMANCE.md](docs/PERFORMANCE.md)**. Two findings matter
most.

**A single wallet is contended at any concurrency, including one.** Quoting the document:

> **6.15% of requests fail with retry exhaustion at a concurrency of one**, where the load generator
> cannot be contending with itself.

The cause is not the load test:

> `SettlementService.settle()` loads and saves **the same wallet row**, on the Kafka consumer thread,
> while the next reservation is being written. […] **a hot wallet contends with its own settlement
> stream.**

> **If you need one number:** on this hardware a single wallet sustains roughly **3 accepted
> transfers/sec** before more than 10% of attempts are rejected, and there is no concurrency at which
> it is clean.

**Acceptance is not settlement.** A transfer returns 202 before the saga runs, so throughput figures
measure how fast the API takes work in, not how fast the system does it. In the baseline scenario:

| Accepted | Settled | In flight at load stop | Backlog drain |
|---:|---:|---:|---:|
| 22,443 | 22,443 | 17,419 | **204s** |

> **Nothing was lost — every accepted transfer settled, zero failures.** But the API accepts work far
> faster than the saga drains it.

### Read these numbers carefully

> **This is docker-compose on a laptop, not a production benchmark.**

They are comparative, not absolute. Run-to-run variance is large — the document records scenarios
late in a long suite reporting **"up to 3.8× lower throughput than the same scenario run on its
own"**, recovering fully when re-run in isolation. Only isolated, position-matched runs are
comparable.

Redis rate limiting and FX caching were measured before and after. The conclusion was **"No effect is
measurable in either direction."** The FX cache in particular: **"The cache works. It just does not
help."** Neither is presented as an improvement, because the measurement does not support one.

The load test suite itself is in [`load-tests/`](load-tests/).

## Rate limiting

Write endpoints are rate limited per authenticated user by a Redis token bucket (`app.rate-limit.*`
in `application.yml`; defaults capacity 60, refill 10/sec). Reads are unlimited, and `/auth/**` is
excluded because it is unauthenticated and there is no user to key a bucket on. Exceeding the limit
returns **429** with an RFC 7807 body, `Retry-After`, and `X-RateLimit-Limit` / `-Remaining` /
`-Reset`; those three headers are on every limited response, not just rejections.

```
HTTP/1.1 429
X-RateLimit-Limit: 60
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 6
Retry-After: 1
Content-Type: application/problem+json
```

The bucket is evaluated by a Lua script inside Redis so the check, decrement and expiry are atomic —
see SPEC.md for why a read-modify-write from Java would defeat the point. **If Redis is unreachable
the limiter fails open** and logs a throttled WARN; the reasoning, and the argument against it, are
on `RateLimiter#degrade`.

## Caching: exchange rates yes, balances never

Exchange rate lookups are cached in Redis, keyed on the currency pair, TTL 10 minutes
(`app.fx-cache.*`). Hit and miss counts are logged so the cache's effect is measurable rather than
assumed. Same-currency transfers short-circuit before the lookup and never touch it. A missing rate
is **not** cached as a negative result, so a rate added later is visible immediately.

**Wallet balances are deliberately not cached, and never should be.** This is a decision, not
something left undone.

A stale balance in a financial system is a correctness bug, not a performance trade-off. The
no-overdraft guarantee is enforced by checking available balance and reserving against a row
protected by an `@Version` column; that check is only meaningful against the current value. Serving
a balance from a cache would mean a wallet could pass an availability check against a number that
was already wrong — which is precisely the overdraft the reservation model exists to prevent. The
window would be small and the failure would be rare, intermittent and financially material: the
worst combination to debug.

Exchange rates are cacheable for the opposite reasons. They are static reference data seeded by a
migration with no live feed, six rows that change never; nothing reserves against them; and a stale
rate within the TTL produces a slightly-off conversion, not a broken invariant. The TTL bounds even
that.

The distinction is not "how hot is this read" but **"what breaks if this value is out of date"**.
For a rate, an approximation. For a balance, the ledger.

## Reads go direct; writes stay async

`GET /transactions/{id}/ledger-entries` is served by wallet-service calling ledger-service
synchronously over HTTP (`/internal/ledger/entries`), while every write still travels the saga —
outbox row, Kafka, and back. The asymmetry is deliberate: a read has no cross-service correctness
requirement that needs saga semantics, and routing it through the saga would mean inventing a
query-side event stream and a read model to serve one endpoint.

The cost is a runtime coupling that writes don't have — if ledger-service is down, that one read
fails while everything else keeps working, which is the right blast radius for it. ledger-service
performs no authorization of its own, so wallet-service checks that the caller is a party to the
transaction *before* making the outbound call, and the port is not published to the host.

## Accounts that predate real authentication

Owners created before real authentication existed have a user row backfilled by the `V5` migration
(one per distinct `owner_id`). They can sign in with email `<owner-id>@example.invalid` and the
password `dev-password-change-me`. This is a development convenience for existing local data, not
something that should ever reach a real deployment.

## Local development without Docker

Requires Java 21, Maven, and Docker (for Postgres, Kafka and Redis).

**Java 21 exactly — not 17, not 24.** Maven runs the compiler and forks test JVMs using whatever
`JAVA_HOME` points at, so the wrong one fails in a way that doesn't name the real problem:

- **Java 17** → `class file version 65.0 ... only recognizes class file versions up to 61.0`
  during `mvn verify`. 65.0 is Java 21; 61.0 is Java 17.
- **Java 24** → compilation fails with `cannot find symbol: getFromWalletId` and similar, because
  the Lombok that ships with Spring Boot 3.3.4 cannot run on it, so no getters are generated.

On macOS with Homebrew this is worse than it sounds: `openjdk@21` is keg-only, so
`/usr/libexec/java_home -v 21` does not find it and silently returns a *different* JDK. Point
`JAVA_HOME` at the Homebrew path directly:

```
export JAVA_HOME=/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home   # Intel
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home # Apple silicon
java -version   # must print 21
```

```
docker compose up -d postgres kafka redis
cd wallet-service && mvn spring-boot:run
cd ledger-service && mvn spring-boot:run
```

## Running the tests

```
cd wallet-service && mvn verify     # and the same in ledger-service
```

`mvn test` runs only the unit tests (`*Test.java`, Surefire). The Testcontainers-backed integration
tests (`*IT.java`) run under Failsafe, which is bound to `verify` — **`mvn test` skips them
silently**, so a green `mvn test` is not evidence the integration suite passed. `verify` needs a
reachable Docker daemon; Testcontainers starts one Postgres (plus Kafka and Redis, only for the
tests that need them) per module, shared across that module's ITs and left running until the JVM
exits, where Ryuk removes it.
