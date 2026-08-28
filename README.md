# Digital Wallet & Payment Ledger

A double-entry payment ledger backend split across two Spring Boot services — `wallet-service` and `ledger-service` — coordinated through a saga + transactional outbox over Kafka, with no distributed transaction anywhere in the system.

See [SPEC.md](SPEC.md) for the full design: architecture, the balance-reservation model that makes the no-overdraft guarantee hold under concurrency, the idempotency story (both client-facing and Kafka-consumer-facing), and the test strategy.

## Status

Deposit, withdrawal, transfer (same- and cross-currency), and reversal all work end-to-end - verified by running the full stack via `docker-compose up` and driving it over HTTP: initiate a deposit, watch the saga carry it through ledger-service and back, and confirm the balance settles correctly. Optimistic-lock retry and the concurrency test suite are in place. Remaining: the Testcontainers-based redelivery integration tests are written but not yet run in CI (see [wallet-service's](wallet-service/src/test/java/com/paymentledger/wallet/transaction/TransactionOutcomeRedeliveryIT.java) and [ledger-service's](ledger-service/src/test/java/com/paymentledger/ledger/posting/TransactionInitiatedRedeliveryIT.java)).

## Running the whole stack

```
docker-compose up -d --build
```

This builds and starts Postgres, Kafka, `wallet-service` (port 8081), and `ledger-service` (port 8082, genuinely internal - `expose`d to the compose network but deliberately not published to the host). First boot is slower (Maven dependency resolution inside the build, Kafka's internal topics settling); `docker compose logs -f wallet-service` shows `Started WalletServiceApplication` when it's ready.

Swagger UI for the client-facing API: `http://localhost:8081/swagger-ui.html`

### Rate limiting

Write endpoints are rate limited per authenticated user by a Redis token bucket (`app.rate-limit.*`
in `application.yml`). Reads are unlimited. Exceeding it returns **429** with an RFC 7807 body,
`Retry-After`, and `X-RateLimit-Limit` / `-Remaining` / `-Reset`; those three headers are on every
limited response, not just rejections.

```bash
curl -i -X POST localhost:8081/accounts -H "Authorization: Bearer $TOKEN"
# HTTP/1.1 429
# X-RateLimit-Limit: 60
# X-RateLimit-Remaining: 0
# X-RateLimit-Reset: 6
# Retry-After: 1
# Content-Type: application/problem+json
```

The bucket is evaluated by a Lua script inside Redis so the check, decrement and expiry are atomic —
see SPEC.md for why a read-modify-write from Java would defeat the point. **If Redis is unreachable
the limiter fails open** and logs a throttled WARN; the reasoning, and the argument against it, are
on `RateLimiter#degrade`.

Redis is `expose`d to the compose network only and is never published to the host.

### Caching: exchange rates yes, balances never

Exchange rate lookups are cached in Redis, keyed on the currency pair, with a configurable TTL
(`app.fx-cache.*`). Hit and miss counts are logged so the cache's effect is measurable rather than
assumed. Same-currency transfers short-circuit before the lookup and never touch it.

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

### Reads go direct; writes stay async

`GET /transactions/{id}/ledger-entries` is served by wallet-service calling ledger-service
synchronously over HTTP (`/internal/ledger/entries`), while every write still travels the saga —
outbox row, Kafka, and back. The asymmetry is deliberate: a read has no cross-service correctness
requirement that needs saga semantics, and routing it through the saga would mean inventing a
query-side event stream and a read model to serve one endpoint.

The cost is a runtime coupling that writes don't have — if ledger-service is down, that one read
fails while everything else keeps working, which is the right blast radius for it. ledger-service
performs no authorization of its own, so wallet-service checks that the caller is a party to the
transaction *before* making the outbound call, and the port is not published to the host.

### Quick smoke test

```bash
TOKEN=$(curl -s -X POST http://localhost:8081/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"demo@example.com","password":"demo-password"}' | jq -r .accessToken)

# Already registered? Swap /auth/register for /auth/login with the same body.

ACCOUNT_ID=$(curl -s -X POST http://localhost:8081/accounts \
  -H "Authorization: Bearer $TOKEN" | jq -r .id)

WALLET_ID=$(curl -s -X POST http://localhost:8081/accounts/$ACCOUNT_ID/wallets \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"currency":"USD"}' | jq -r .id)

curl -s -X POST http://localhost:8081/wallets/$WALLET_ID/deposits \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-1" -d '{"amount":100.00}'

# a few seconds later, once the saga has settled:
curl -s http://localhost:8081/wallets/$WALLET_ID -H "Authorization: Bearer $TOKEN"
```

Access tokens last 15 minutes. To get a fresh pair, POST the `refreshToken` from the response
above to `/auth/refresh`. Refresh tokens rotate on every use, so the old one stops working the
moment you use it — and presenting an already-used one revokes every token for that user, on the
assumption that a token coming back a second time was stolen rather than replayed by accident.

### Accounts that predate real authentication

Owners created under the old mock-token endpoint have a user row backfilled by the `V5` migration
(one per distinct `owner_id`). They can sign in with email `<owner-id>@example.invalid` and the
password `dev-password-change-me`. This is a development convenience for existing local data, not
something that should ever reach a real deployment.

## Local development without Docker

Requires Java 21, Maven, and Docker (for Postgres + Kafka only).

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
docker compose up -d postgres kafka
cd wallet-service && mvn spring-boot:run
cd ledger-service && mvn spring-boot:run
```

### Running the tests

```
cd wallet-service && mvn verify     # and the same in ledger-service
```

`mvn test` runs only the unit tests (`*Test.java`, Surefire). The Testcontainers-backed
integration tests (`*IT.java`) run under Failsafe, which is bound to `verify` — `mvn test` skips
them silently. `verify` needs a reachable Docker daemon; Testcontainers starts one Postgres (and
one Kafka, only for the tests that need it) per module, shared across that module's ITs and left
running until the JVM exits, where Ryuk removes it.
