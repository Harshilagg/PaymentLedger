# Digital Wallet & Payment Ledger

A double-entry payment ledger backend split across two Spring Boot services — `wallet-service` and `ledger-service` — coordinated through a saga + transactional outbox over Kafka, with no distributed transaction anywhere in the system.

See [SPEC.md](SPEC.md) for the full design: architecture, the balance-reservation model that makes the no-overdraft guarantee hold under concurrency, the idempotency story (both client-facing and Kafka-consumer-facing), and the test strategy.

## Status

Under active development, following the build order in SPEC.md. Not yet runnable end-to-end.

## Local development

Requires Java 21, Maven, and Docker (for Postgres + Kafka via `docker-compose`).

```
docker-compose up -d
cd wallet-service && mvn spring-boot:run
cd ledger-service && mvn spring-boot:run
```
