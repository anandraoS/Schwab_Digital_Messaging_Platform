# Runbook: Provider Rate Limiting

## Symptoms and alerts
`messaging.provider.calls{outcome="RATE_LIMITED"}` rising (provider returned 429/throttle); `messaging.ratelimit.rejected{scope}` for our own limiter; backlog growing for that channel; `Retry-After` hints from provider.

## Impact
Throughput limited; retries with backoff; LOW/NORMAL delayed; CRITICAL prioritized by queue priority but shares the same provider quota.

## Two layers of limiting
| Layer | Config | Behavior |
|---|---|---|
| Our token bucket (per provider) | `messaging.providers.<id>.rate-per-sec` | empty bucket => requeue with short delay, no attempt consumed |
| Provider 429 | outcome RATE_LIMITED | `RETRY_PENDING` with backoff (uses an attempt), honors Retry-After as minimum |
If we see many provider 429s, our configured rate is above the vendor's real limit (or other consumers share the quota).

## Diagnose
1. `GET /api/v1/providers` : `rateLimit`, `successRate`.
2. Compare `rate(messaging_provider_calls_total)` with configured rate x instance count (with Redis down, buckets are per instance: `redis-outage.md`).
3. Bulk job contribution (`GET /api/v1/bulk-jobs`).
4. Vendor quota changes or burst limit vs sustained limit.

## Mitigate
1. Lower `rate-per-sec` to below the observed accepted rate (config change via `PUT /api/v1/providers/{providerId}/config`, PLATFORM_ADMIN).
2. Throttle/stop bulk jobs so CRITICAL/HIGH keep the quota.
3. Add a second provider/route if available (`provider-outage.md` mitigation).
4. Request a quota increase from the vendor (real environments).
5. Do not add consumers.

## Recover
Backlog drains at the sustainable rate. Compute ETA: `depth / rate`.

## Verify
`RATE_LIMITED` outcomes near zero; queue age falling.

## Prevent
Configure from vendor contracts, per-priority reservation (aging/priority specs), load tests with `RATE_LIMIT` fault mode (local/test only).

Proof: `FailureRecoveryIT#scenario10_providerRateLimitAndBackpressure_noAttemptConsumed_apiReturns503`.
