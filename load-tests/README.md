# Load tests

k6 scenarios for measuring wallet-service. Results and methodology live in
[docs/PERFORMANCE.md](../docs/PERFORMANCE.md).

Nothing here adds a Java or Node dependency. k6 runs from its official Docker image, joined to the
compose network; the seeder is bash + curl + python3.

## Running

```bash
docker compose up -d --build          # stack must be running

./load-tests/run.sh contention        # one scenario
./load-tests/run.sh all               # all four, ~35-45 min on a 2-core laptop
```

`run.sh` does the whole cycle per scenario: **reset → seed → measure → settlement**.

### The reset is mandatory

`run.sh` starts each scenario with `docker compose down -v`, which drops the Postgres volume so
every table starts empty. This is not tidiness. A measured run leaves thousands of rows in
`transaction` and `idempotency_record`; a later run against those larger tables is slower for
reasons that have nothing to do with whatever change is being evaluated. Comparing a clean baseline
against a dirty re-run is how a regression gets blamed on a cache that did not cause it.

`SKIP_RESET=1` exists for iterating on the harness itself. Numbers produced with it are not
comparable to anything and must not be put in the results table.

## Layout

| Path | Purpose |
|---|---|
| `seed.sh` | Creates users/accounts/wallets, funds them, **waits for the saga to settle**, writes `seed.json` |
| `settlement.sh` | After a run: drains the backlog and reports accepted / settled / drain time |
| `run.sh` | Orchestrates reset → seed → k6 → settlement |
| `lib/config.js` | VU ladders, step timing, threshold wiring |
| `lib/api.js` | Request helpers, unique idempotency keys, status-code counters |
| `scenarios/*.js` | The four scenarios |
| `results/` | JSON summaries (git-ignored) |

## The two things most likely to mislead you

**A transfer returns 202, not 200.** The API accepts the transfer and the saga settles it
afterwards, so every latency and throughput number from k6 is **acceptance**, not settlement. If the
generator outruns the Kafka consumer, a backlog builds while the API still looks fast. That is
exactly why `settlement.sh` exists and why the results table reports acceptance rate, settlement
rate and backlog drain time as three separate numbers.

The contention scenario is the exception, and deliberately so: the balance reservation and the
`@Version` conflict both happen synchronously *before* the 202 is written, so its 503 curve is a
real measurement of lock contention rather than an artefact of queueing.

**Every transfer needs a unique `Idempotency-Key`.** A repeated key returns the first response out
of the dedupe cache, which measures that path instead of a real transfer. Keys are built from
`run-id + VU + iteration`, so they are unique by construction rather than by trusting an RNG.

## Seeding

`seed.sh` funds wallets with a deposit, and **a deposit is also asynchronous** — the balance is
still zero the instant it returns. The seeder therefore polls `wallet.transaction` until nothing is
`PENDING` before declaring success. Skipping that wait produces a wall of 422 insufficient-funds
responses, and the contention scenario would then be measuring funding rather than lock contention.

Defaults are sized to the ladders: `USERS=80` (the largest throughput rung, so no two baseline VUs
share a source wallet) and `DESTINATIONS=40` (the largest contention rung, so each VU gets its own
destination).

## Tuning

| Variable | Default | Notes |
|---|---|---|
| `STEPS` | per-scenario | Comma-separated VU rungs |
| `WARMUP_SECONDS` | `15` | Discarded from every rung |
| `MEASURE_SECONDS` | `45` | Reported window |
| `USERS` / `DESTINATIONS` | `80` / `40` | Seed pool sizes |
| `BASE_URL` | `http://wallet-service:8081` | Compose service name, not localhost |

Any override must be recorded next to the numbers it produced.
