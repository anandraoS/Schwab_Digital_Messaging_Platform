# Runbook: Retry Storm

## Symptoms and alerts
`RetryStorm`; `rate(messaging_retry_scheduled_total)` high and sustained; provider calls per second above normal while success is low; provider `RATE_LIMITED` outcomes; CPU/DB load up; same recipients repeated.

## Impact
Load amplification on a degraded provider and on our DB/broker; delays unrelated messages; potential provider blocking.

## Diagnose
1. Provider outcomes: `messaging_provider_calls_total` by outcome: mostly TRANSIENT/TIMEOUT/RATE_LIMITED?
2. Check effective backoff: config `messaging.dispatch.backoff-base-ms`, `backoff-cap-ms`, `max-attempts` (a misconfigured base of 0 or cap too low causes storms). Verify via `GET /actuator/configprops` if exposed internally or deployment config.
3. Look for synchronization: retries clustered at the same second means jitter missing/disabled (bug) or clock mocked.
4. Is the circuit breaker open/closed? A breaker that never opens (threshold too high) allows hammering.
5. Look for requeue loops: rate-limiter requeues are cheap but could loop if token rate is 0 (`rate-per-sec=0`).

## Mitigate
1. Disable or deprioritize the failing provider (`PUT /api/v1/providers/{id}/config`).
2. Increase `backoff-cap-ms` / `backoff-base-ms` (config rollout) and reduce `dispatch.concurrency`.
3. Pause consumers briefly (`POST /api/v1/ops/consumers/pause`) to let the provider recover; messages remain queued/RETRY_PENDING. Resume gradually.
4. Stop bulk jobs.

## Recover
Resume consumers; watch for a second wave (all paused messages waking at once): the jitter spreads it; if not, resume in steps (concurrency 2, 4, 8).

## Verify
Retry rate falls; success rate up; no growth in DLQ beyond expected.

## Prevent
Full jitter (ADR-0006), breaker thresholds tested, retries share provider token bucket, max-attempt caps, load tests with fault injection (`PUT /providers/{id}/fault-mode` local/test only).

Proof: `RetryPolicyTest` (bounds), `FailureRecoveryIT#scenario01_...`, `#scenario10_...`.
