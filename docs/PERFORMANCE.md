# Performance

Measured numbers for wallet-service, and the method that produced them.

## Read this first

**This is docker-compose on a laptop, not a production benchmark.** Postgres, Kafka, both Spring
services *and* the load generator run on the same two physical cores. k6 competes for CPU with the
system it is measuring. The absolute throughput here says nothing about what this design would do on
real hardware.

They are still worth having, because every number is produced the same way against the same box —
but only some of them are reproducible enough to compare. See
[what is and isn't trustworthy](#what-is-and-isnt-trustworthy) before drawing any conclusion from a
difference between two runs. A caveated real number beats an uncaveated misleading one.

### Hardware

| | |
|---|---|
| CPU | Intel Core i3-1000NG4 @ 1.10GHz — **2 physical cores**, 4 logical |
| RAM | 8 GB |
| OS | macOS 14.5 |
| Docker | Docker Desktop 29.7.2, limited to 4 CPUs / 3.8 GB |
| Under test | postgres:16-alpine, apache/kafka:3.7.0, wallet-service, ledger-service |
| Generator | grafana/k6 (container, same host, same cores) |
| Date | 2026-08-27 |

---

## What is and isn't trustworthy

The suite was run twice end to end. Comparing the two runs is the only honest way to know which
numbers mean anything.

**The contention 503 curve is highly reproducible** — within ±2.3 percentage points at every rung:

| VUs | 1 | 2 | 3 | 5 | 8 | 12 | 20 | 40 |
|---|---|---|---|---|---|---|---|---|
| run A | 5.12% | 13.62% | 24.65% | 34.80% | 46.47% | 43.36% | 38.85% | 41.19% |
| run B | 6.15% | 15.09% | 22.39% | 36.00% | 46.96% | 41.86% | 38.97% | 42.00% |

**Throughput is not.** Same scenario, same rung, two runs:

| VUs | 5 | 10 | 20 | 40 | 80 |
|---|---|---|---|---|---|
| run A req/s | 5.7 | 11.0 | 17.2 | 22.1 | 24.6 |
| run B req/s | 6.8 | 13.1 | 10.9 | **5.4** | 21.6 |
| spread | 1.2× | 1.2× | 1.6× | **4.1×** | 1.1× |

So, concretely:

- **A throughput difference smaller than about 2× on this hardware is noise.** Do not read a 20%
  improvement as an improvement.
- **A change of a few percentage points in the 503 rate is real.**

The likely cause of the throughput variance is the settlement backlog: while it drains it competes
for the same two cores as the API, so a run's numbers depend on how much of the previous phase's
backlog was still draining underneath it. That is a property of the hardware, not of the code.

---

## Acceptance is not settlement

`POST /wallets/{id}/transfers` returns **202 Accepted**. The transfer is recorded and an outbox row
written; the money moves later through `outbox → Kafka → ledger-service → Kafka → settlement`.

So **every latency and throughput figure below is acceptance, not settlement** — how fast the API
takes work in, not how fast the system does it. Measured separately:

| Scenario | Accepted | Settled | In flight at load stop | Backlog drain |
|---|---:|---:|---:|---:|
| Baseline | 22,443 | 22,443 | 17,419 | **204s** |
| Contention | 16,096 | 16,096 | 4,137 | 33s |
| Read-heavy | 6,631 | 6,631 | 1,223 | 12s |
| Cross-currency | 31,411 | 31,411 | 24,768 | **291s** |

**Nothing was lost — every accepted transfer settled, zero failures.** But the API accepts work far
faster than the saga drains it: baseline finished its load with 17,419 transfers still in flight and
took another 3½ minutes to catch up. Anyone reading "24 req/s accepted" as "24 transfers/sec of
throughput" would be wrong by a wide margin.

**The contention scenario is the exception, deliberately.** Its balance reservation and `@Version`
conflict both happen synchronously, inside the transaction, *before* the 202 is written. Its 503
curve measures real lock contention, not queueing — which is also why it is the one number that
reproduces cleanly.

---

## Scenario 2 — Contention (the headline result)

All VUs transfer **out of one shared wallet**, each **into its own receive-only destination**, so the
only contended row is the source. A 503 is `OptimisticLockRetrier` exhausting its 5 bounded attempts
(`ObjectOptimisticLockingFailureException` → 503).

| VUs | req/s | p50 ms | p95 ms | p99 ms | accepted | 503 | 503 % | 422 | 409 |
|----:|------:|-------:|-------:|-------:|---------:|----:|------:|----:|----:|
| 1 | 2.9 | 21.1 | 89.9 | 158.6 | 1297 | 85 | **6.15** | 0 | 0 |
| 2 | 3.8 | 32.0 | 129.5 | 224.1 | 1564 | 278 | 15.09 | 0 | 0 |
| 3 | 1.7 | 68.7 | 603.8 | 1805.5 | 631 | 182 | 22.39 | 0 | 0 |
| 5 | 5.7 | 64.2 | 205.8 | 296.7 | 1749 | 984 | 36.00 | 0 | 0 |
| 8 | 5.9 | 107.7 | 307.6 | 471.8 | 1509 | 1336 | **46.96** | 0 | 0 |
| 12 | 5.6 | 169.8 | 419.8 | 723.1 | 1565 | 1127 | 41.86 | 0 | 0 |
| 20 | 6.7 | 251.7 | 611.8 | 925.0 | 1962 | 1253 | 38.97 | 0 | 0 |
| 40 | 5.8 | 534.1 | 1502.2 | 3090.1 | 1606 | 1163 | 42.00 | 0 | 0 |

### There is no clean breaking point — it is already failing at concurrency 1

The 1-VU row is the finding. **6.15% of requests fail with retry exhaustion at a concurrency of one**,
where the load generator cannot be contending with itself.

The cause is not the load test. `SettlementService.settle()` loads and saves **the same wallet row**,
on the Kafka consumer thread, while the next reservation is being written. The conflict is between a
new transfer's synchronous reservation and the asynchronous settlement of *earlier* transfers on that
same row — **a hot wallet contends with its own settlement stream.** Concurrency makes it worse but
was never required to trigger it.

Beyond 3 VUs, throughput is flat at roughly 6 req/s while latency grows steadily and failures sit
near 40-47%. That flatness is the signature of a hard serialization point: the row is the
bottleneck, so added concurrency buys no extra work, only more losers and more queueing.

**If you need one number:** on this hardware a single wallet sustains roughly **3 accepted
transfers/sec** before more than 10% of attempts are rejected, and there is no concurrency at which
it is clean.

The reading is trustworthy because **422 and 409 are zero at every rung** — wallets never ran dry, so
this is not a funding artefact, and no idempotency key was reused, so the dedupe path was never
measured by mistake.

---

## Scenario 1 — Baseline throughput

Distinct source wallet per VU, all sending to receive-only wallets that are never anyone's source.

| VUs | req/s | p50 ms | p95 ms | p99 ms | accepted | 503 | 422 | 409 |
|----:|------:|-------:|-------:|-------:|---------:|----:|----:|----:|
| 5 | 6.8 | 85.4 | 279.5 | 454.7 | 2037 | 2 | 0 | 0 |
| 10 | 13.1 | 95.1 | 252.9 | 394.4 | 3948 | 0 | 0 | 0 |
| 20 | 10.9 | 171.5 | 776.5 | 2314.9 | 3274 | 0 | 0 | 0 |
| 40 | 5.4 | 678.8 | 3310.3 | 6382.4 | 1616 | 0 | 0 | 0 |
| 80 | 21.6 | 466.1 | 1346.8 | 1804.0 | 6503 | 0 | 0 | 0 |

Removing the shared row removes essentially all retry exhaustion — 2 events in 22,443 transfers,
against 40%+ when a single wallet is shared. **Contention is a property of the access pattern, not
of the transfer path.**

The 20/40-VU rows are visibly out of line with their neighbours and with run A. Reported as measured
rather than smoothed; see [what is and isn't trustworthy](#what-is-and-isnt-trustworthy). The
defensible reading is that acceptance throughput plateaus somewhere around **10-25 req/s** on this
box and the shape of the curve between those points is not resolvable here.

### A residual that cannot be engineered away

Even here, each source wallet is written both by its own reservations and by the settlement of its
own earlier transfers — the same mechanism as the 1-VU contention result. It is invisible in this
scenario only because each wallet sees little traffic. It is inherent to the design, not a flaw in
the benchmark, and no access pattern removes it.

---

## Scenario 3 — Read-heavy mix

~90% reads (`GET /wallets/{id}`, `GET /wallets/{id}/transactions`) to ~10% transfers. Reads are fully
synchronous, so these latencies are real end-to-end cost with nothing deferred to the saga.

| VUs | req/s | p50 ms | p95 ms | p99 ms | transfers accepted | 503 |
|----:|------:|-------:|-------:|-------:|-------------------:|----:|
| 5 | 17.6 | 29.8 | 112.8 | 209.6 | 498 | 0 |
| 10 | 27.2 | 40.6 | 145.7 | 253.6 | 810 | 0 |
| 20 | 33.0 | 73.5 | 210.1 | 366.8 | 1030 | 0 |
| 40 | 40.0 | 122.6 | 360.5 | 555.3 | 1202 | 0 |
| 80 | 48.0 | 203.3 | 640.8 | 943.9 | 1453 | 0 |

The cleanest curve in the suite and the highest throughput — **48 req/s at 80 VUs, roughly double
baseline** — which is expected: most requests never write a row, never touch the outbox, and never
enter the saga. It also drains in 12s rather than minutes, because only a tenth of its traffic
creates settlement work.

This scenario is the most sensitive to table size, which is why the state reset below is mandatory.

---

## Scenario 4 — Cross-currency transfers

Every transfer crosses currencies, so each performs an `exchange_rate` lookup. `FxConverter.convert()`
short-circuits when currencies match and never touches the table, so no other scenario exercises this
path at all.

| VUs | req/s | p50 ms | p95 ms | p99 ms | accepted | 503 |
|----:|------:|-------:|-------:|-------:|---------:|----:|
| 5 | 5.4 | 106.5 | 365.5 | 566.1 | 1625 | 2 |
| 10 | 11.4 | 112.5 | 287.9 | 403.0 | 3429 | 0 |
| 20 | 17.4 | 149.6 | 331.9 | 683.5 | 5232 | 0 |
| 40 | 19.6 | 261.1 | 680.4 | 1097.3 | 5887 | 0 |
| 80 | 25.3 | 406.5 | 1127.9 | 1516.9 | 7595 | 0 |

**Cross-currency is indistinguishable from same-currency** (baseline: 6.8 / 13.1 / 10.9 / 5.4 / 21.6).
Every difference is well inside the noise floor established above — and cross-currency actually ran
*faster* than baseline at 20 and 40 VUs, which is only possible if the FX lookup is not a meaningful
cost.

Recorded here **before** the cache is built, deliberately: six rows behind an index sitting in
Postgres shared buffers is already close to free, so there is little for a cache to remove. If
commit 3 shows no improvement, this table is why, and that result will be reported as measured.

---

## Reproducing

```bash
docker compose up -d --build
./load-tests/run.sh all          # ~60 min on the hardware above
python3 load-tests/report.py     # regenerates every table above
```

### State reset is mandatory, not hygiene

`run.sh` runs `docker compose down -v` before every measured scenario, dropping the Postgres volume
so all tables start empty. A single run leaves >20,000 rows in `transaction`; re-measuring against
those larger tables is slower for reasons unrelated to whatever change is being evaluated, and
read-heavy is especially sensitive to it. **Comparing a clean baseline against a dirty re-run is how
a regression gets attributed to a change that did not cause it.** Numbers produced with
`SKIP_RESET=1` are not comparable and must not appear here.

### Step timing

Each rung is **held for 60 seconds, of which the first 15 seconds are discarded as warm-up** and the
remaining **45 seconds** reported. The JVM is cold on the first rung and would otherwise make the
low-concurrency rows look far worse than steady state. The discard is structural — warm-up and
measurement are separate k6 scenarios with different tags — not a filter applied afterwards.

Two ladders: throughput scenarios climb 5→80 VUs; contention starts at **1**. A pilot showed
single-wallet contention already failing at 2 VUs, so a ladder starting at 5 would have begun above
the interesting region and reported a breakpoint that had already passed.

### A harness bug worth recording

The first baseline paired VUs in a ring — VU *i* sourced from `users[i-1]` and sent to `users[i]` —
making every wallet simultaneously one VU's source and another's destination, so a reservation and a
settlement-credit wrote the same row. It measured 0.52% retry exhaustion at 5 VUs. Small, but it
meant the "no contention" baseline contained contention. Fixed with a dedicated receive-only wallet
pool, and every number above was re-measured with the corrected harness. Kept in this document
because the failure mode is easy to reintroduce.

---

## Results log

| Date | Commit | What changed |
|---|---|---|
| 2026-08-27 | commit 1 | Baseline recorded. No production code changed. |
