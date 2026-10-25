# Performance Test Plan

**Execution status: NOT executed.** No k6, Docker or load environment exists on the authoring machine. The k6 scripts in
`performance/k6/` are unrun. The Node driver `performance/node-load/load-driver.mjs` is runnable locally but no measured
result is recorded in this repository yet. Do not quote any throughput or latency figure from this repo until the results
table in `performance/README.md` is filled from a real run.

## Illustrative SLOs (starting points, not measured guarantees)
| SLI | Objective | Where measured |
|---|---|---|
| API availability (non-5xx on `/api`) | 99.9% monthly | recording rule `sli:api_error_ratio:*` |
| Submit latency | p95 < 300 ms, p99 < 800 ms | k6 threshold on `POST /messages`; `sli:api_latency_p95_seconds:rate5m` |
| Read latency (lists) | p95 < 500 ms | k6 `db-throughput.js`, `dashboard-queries.js` |
| Dispatch delay (accepted to provider submit) | p95 < 30 s for NORMAL priority | `messaging.dispatch.delay` timer |
| Provider failure ratio | < 20% sustained (alert threshold) | `sli:provider_failure_ratio:rate5m` |

## Scenarios and what each proves
| Script | Question it answers | Pass signals | Typical failure meaning |
|---|---|---|---|
| submit-high-volume | Does the accept path hold at 150 req/s with idempotency + outbox writes? | 202 rate > 99%, p95 < 300 ms | pool saturation, slow idempotency claim, missing index |
| concurrent-consumers | How does drain rate scale with `messaging.dispatch.concurrency`? | queue depth sample < 5000, stable in-flight | consumer starvation, provider limiter too tight |
| provider-rate-limit | Does a provider rate limit degrade gracefully (requeue, no 5xx, no attempt burn)? | no 5xx, retries bounded | attempts consumed on rate limit, hot loops |
| bulk-processing | Throughput of the Spring Batch job end to end | terminal COMPLETED(_WITH_ERRORS), rows/s above floor | chunk/commit tuning, skip policy cost |
| db-throughput | Mixed read/write DB behaviour | read p95 < 500 ms while writing | N+1, missing index, Hikari pending > 0 |
| backlog-recovery | Time to drain a backlog after consumers resume | drain < 10 min for 5000 msgs (illustrative) | slow consumers, DLQ spill |
| retry-storm | Are retries bounded (backoff + jitter) under total provider failure? | API 202 stays healthy, retries do not exceed accept rate x attempts, system recovers | retry amplification, synchronized retries |
| dashboard-queries | Can 30 operators poll the console without hurting dispatch? | p95 < 400 ms on ops endpoints | heavy aggregate queries needing caching |

## Method
1. Warm the JVM (the Node driver does a 2 s warm-up; for k6 discard the first minute).
2. Run one scenario at a time on an otherwise idle machine; note CPU count and the active profile (H2 + in-memory bus behaves very differently from PostgreSQL + RabbitMQ).
3. Capture: tool output, Grafana dashboards "API and SLO", "Dispatch and Queues", "JVM and DB Pool", backend logs for errors.
4. Compare against thresholds; investigate with the matching runbook in `docs/operations/runbooks/`.
5. Record the run in `performance/README.md` results table (date, commit, environment, command, outcome).

## Honesty notes
- Local runs use H2 and the in-memory bus; those numbers say nothing about PostgreSQL/RabbitMQ capacity.
- Simulated providers have artificial latency and fault models; "provider rate limit" here exercises our limiter and requeue logic, not a real provider.
- Fault-injection endpoints exist only in profiles `local`/`test`, so retry-storm and provider-rate-limit cannot run against `prod-like`.
