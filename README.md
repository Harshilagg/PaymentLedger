# Digital Wallet & Payment Ledger

A double-entry payment ledger backend split across two Spring Boot services — `wallet-service` and `ledger-service` — coordinated through a saga + transactional outbox over Kafka, with no distributed transaction anywhere in the system.

See [SPEC.md](SPEC.md) for the full design: architecture, the balance-reservation model that makes the no-overdraft guarantee hold under concurrency, the idempotency story (both client-facing and Kafka-consumer-facing), and the test strategy.

## Status

Deposit, withdrawal, transfer (same- and cross-currency), and reversal all work end-to-end - verified by running the full stack via `docker-compose up` and driving it over HTTP: initiate a deposit, watch the saga carry it through ledger-service and back, and confirm the balance settles correctly. Optimistic-lock retry and the concurrency test suite are in place. Remaining: the Testcontainers-based redelivery integration tests are written but not yet run in CI (see [wallet-service's](wallet-service/src/test/java/com/paymentledger/wallet/transaction/TransactionOutcomeRedeliveryIT.java) and [ledger-service's](ledger-service/src/test/java/com/paymentledger/ledger/posting/TransactionInitiatedRedeliveryIT.java)).

## Running the whole stack

```
docker-compose up -d --build
```

This builds and starts Postgres, Kafka, `wallet-service` (port 8081), and `ledger-service` (port 8082, internal-only - no client-facing API). First boot is slower (Maven dependency resolution inside the build, Kafka's internal topics settling); `docker compose logs -f wallet-service` shows `Started WalletServiceApplication` when it's ready.

Swagger UI for the client-facing API: `http://localhost:8081/swagger-ui.html`

### Quick smoke test

```bash
TOKEN=$(curl -s -X POST http://localhost:8081/auth/token \
  -H "Content-Type: application/json" -d '{"ownerId":"<any-uuid>"}' | jq -r .token)

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

## Local development without Docker

Requires Java 21, Maven, and Docker (for Postgres + Kafka only).

```
docker compose up -d postgres kafka
cd wallet-service && mvn spring-boot:run
cd ledger-service && mvn spring-boot:run
```
