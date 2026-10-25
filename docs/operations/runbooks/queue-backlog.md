# Runbook: Queue Backlog

## Symptoms and alerts
`QueueBacklogGrowing`; `messaging.queue.depth{priority}` rising; oldest-message age above SLO; `messaging.dispatch.delay` p95 up; API returns 503 `Retry-After` when `max-queue-depth` exceeded.

## Impact
Dispatch delay; CRITICAL traffic protected by priority but affected at extreme depth; clients see 503 with `Retry-After` at the backpressure threshold.

## Diagnose (decide: producers too fast, consumers too slow, or downstream throttled)
1. `GET $BASE/api/v1/ops/queues` : depth per priority, in-flight, consumers, retry-pending, oldest age.
2. Producers: `rate(messaging_messages_accepted_total[5m])` by tenant/channel (bulk job? `GET /api/v1/bulk-jobs`).
3. Consumers: are workers alive (`messaging.queue.inflight` > 0)? Paused (`/ops/consumers/pause` used)? Thread dump if stuck (see performance guide: `jcmd <pid> Thread.print`).
4. Downstream: `messaging.provider.latency`, `RATE_LIMITED` outcomes, circuit state. A slow provider with fixed concurrency means throughput = concurrency / latency.
5. DB: `hikaricp_connections_pending`.
6. Broker health (Rabbit management UI/`rabbitmqctl list_queues name messages consumers`) in real environments.

## Mitigate
| Cause | Action |
|---|---|
| Bulk job flooding | Stop or throttle job: `POST /api/v1/bulk-jobs/{id}/stop`; reduce `rate_limit_per_sec`; ensure bulk priority LOW |
| Consumers paused | `POST /api/v1/ops/consumers/resume` |
| Consumers too few and provider can take more | Increase replicas or `messaging.dispatch.concurrency` stepwise (e.g., 8 to 12) while watching provider `RATE_LIMITED` and DB pool |
| Provider slow/rate-limited | More consumers will NOT help: see `provider-outage.md` / `provider-rate-limit.md` |
| Stuck consumers | Restart instances one by one (graceful shutdown returns unacked to queue) |
| Starvation of LOW | Priority aging (spec 0001) if enabled; otherwise accept |

## Recover
Watch depth and age trend down. Do not purge queues: messages are persisted in DB but purging the broker loses envelopes (recoverable only by operator requeue from DB states: avoid).

## Verify
Oldest age within SLO; `dispatch.delay` p95 normal; no unexpected 503s.

## Escalate
If CRITICAL oldest age > 60 s for 10 min: page platform lead.

## Prevent
Capacity model (consumers x per-message latency vs provider limits), bulk throttles, alerts on trend not only level, load test (`performance/`, not executed here), backpressure tuned.

Proof: `FailureRecoveryIT#scenario10_providerRateLimitAndBackpressure_noAttemptConsumed_apiReturns503`.
