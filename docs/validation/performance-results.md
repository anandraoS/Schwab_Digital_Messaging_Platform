# Performance measurements (executed, local, single machine)

Only numbers produced by the runs below are recorded. Raw console output: `docs/validation/performance-run-output.txt`;
per-run JSON: `performance/node-load/results/*.json`.

## Machine and configuration caveats (read before quoting any number)
- Laptop, Windows 11, 8 logical CPUs (Intel i5-1135G7 @ 2.40 GHz), 16 GiB RAM. **The load generator (Node 24), the backend (GraalVM JDK 21.0.7) and
  everything else ran on the same machine**, so client and server compete for CPU. Numbers are for relative comparison and sanity, not capacity planning.
- Backend: packaged jar `backend/target/messaging-platform.jar`, profile `local`, **default configuration** (H2 in-memory in PostgreSQL mode, in-memory bus
  with 8 consumer threads, in-memory coordination, in-memory Mongo, simulators with fault mode NONE, API rate limit 50 req/s burst 100 **per client subject**,
  backpressure threshold 5,000). Port 18280 (`SERVER_PORT=18280 bash scripts/run-local.sh`), fresh JVM for the whole run sequence. Not PostgreSQL, not RabbitMQ.
- The driver spreads load over 32 sender subjects (`--clients 32`, default) because one subject only measures the rate limiter. LOW priority is not used
  (quiet-hours deferral of SERVICE/LOW traffic would leave deferred rows behind; deferred rows stay VALIDATED by design).
- Single run per scenario, no repetition, so no confidence intervals. JIT warm-up of 5 s was discarded.
- k6 scripts (`performance/k6/*`) were **NOT executed**: k6 is not installed here.

## Commands (repository root; `B=http://localhost:18280`)
```
node performance/node-load/load-driver.mjs --base-url $B --scenario health --concurrency 8 --duration 10
node performance/node-load/load-driver.mjs --base-url $B --scenario submit --concurrency 8  --duration 30 --warmup 5
node performance/node-load/wait-drain.mjs  --base-url $B
node performance/node-load/load-driver.mjs --base-url $B --scenario submit --concurrency 32 --duration 30 --warmup 5
node performance/node-load/wait-drain.mjs  --base-url $B
node performance/node-load/load-driver.mjs --base-url $B --scenario read   --concurrency 16 --duration 20 --warmup 3
node performance/node-load/load-driver.mjs --base-url $B --scenario idempotent --requests 400 --concurrency 8 --warmup 0
node performance/node-load/load-driver.mjs --base-url $B --scenario submit --concurrency 8 --duration 20 --warmup 2 --fault TRANSIENT --fault-probability 1 --settle 60
```
(`.work/perf-run.sh` is the exact sequence that was executed.) Simulators were in NONE mode except in the last run.

## Results
| Scenario | Throughput | p50 | p95 | p99 | Status counts |
|---|---|---|---|---|---|
| Health (`/actuator/health/readiness`), 8 clients, 10 s | 862 req/s | 5.6 ms | 28.0 ms | 66.8 ms | 8,661 x 200 |
| Submit `POST /messages` (mixed EMAIL/SMS/PUSH), 8 clients, 30 s | 178.9 req/s | 24.5 ms | 134.8 ms | 200.4 ms | 5,375 x 202 |
| Submit, 32 clients, 30 s | 297.2 req/s offered; 262 req/s accepted | 77.7 ms | 309.6 ms | 487.4 ms | 7,859 x 202, 1,069 x 503 (API backpressure) |
| Dashboard/read mix (list 50 / ops summary / ops queues), 16 clients, 20 s | 262 req/s | 37.3 ms | 184.1 ms | 266.4 ms | 5,248 x 200 |
| - `GET /messages?size=50` | 99 req/s | 11.4 ms | 119.0 ms | 174.6 ms | |
| - `GET /ops/summary` | 123 req/s | 81.5 ms | 219.8 ms | 312.3 ms | |
| - `GET /ops/queues` | 40 req/s | 8.7 ms | 112.2 ms | 181.0 ms | |
| Idempotent replay (same key), 8 clients, 400 requests | 478 req/s | 13.7 ms | 28.7 ms | 87.3 ms | 1 x 202, 140 x 200 (replay), 259 x 429 (single subject hit the 50 req/s API limit: expected) |

Asynchronous pipeline (accept -> outbox -> bus -> consumer -> simulated provider -> SENT_TO_PROVIDER):
- After the 8-client run, 3,952 messages were still waiting; they drained at about **121 msg/s** end-to-end (31.5 s). After the 32-client run, 4,847 drained at about
  **201 msg/s** (22.2 s). So on this machine the API accepts faster (170-300 req/s) than the pipeline dispatches (about 120-200 msg/s), a backlog builds
  and API backpressure (HTTP 503 + `Retry-After`) engages at 5,000 pending, which is the designed protection.
- 147 / 377 / 584 messages stayed `VALIDATED` after the plateau: these are SERVICE-category pushes (price alert template) for recipients currently inside their
  quiet hours (recipient time zones such as Asia/Tokyo at the time of the run), deferred by policy, not stuck. `wait-drain.mjs` treats a 15 s plateau as drained.

### Retry-storm style run (simulated-email TRANSIENT, probability 1, for the 20 s submit phase)
- 7,731 submissions accepted (202) at about 408 req/s, 456 rejected with 503 backpressure; accept p50/p95/p99 = 11.8 / 59.9 / 140.6 ms (note: lower than the
  healthy run because emails were failing fast and the machine was busy elsewhere; do not compare across runs).
- At the end of the storm: simulated-email circuit breaker `HALF_OPEN`, 807 deliveries delayed in the bus (requeued without consuming attempts while the
  breaker refused calls), 0 RETRY_PENDING snapshot at that instant, DLQ depth 0.
- Fault reset to NONE, 60 s later: circuit `CLOSED`, `VALIDATED` 584 (quiet-hours deferrals only), `SENT_TO_PROVIDER` 19,834 total, DLQ 0: the platform recovered
  without operator action and without dead letters in that window.

## What these numbers do not show
No PostgreSQL/RabbitMQ/Redis cost, no network latency, no multi-node behaviour, no sustained (hours) load, no k6 ramping profiles, no GC/heap analysis.
`/ops/summary` p50 of 81 ms with about 20k rows is the slowest read: it aggregates over the 24 h window (see `docs/database/query-plans-executed.md`).
