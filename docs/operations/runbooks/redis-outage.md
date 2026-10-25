# Runbook: Redis Outage

## Symptoms and alerts
`RedisFallbackActive` (`messaging.redis.fallback` incrementing), Lettuce connection errors in logs, slightly higher latency on POST /messages.

## Impact
By design low: Redis is a fast path only. `ResilientCoordination` falls back: idempotency to the DB unique constraints, rate limiting to per-instance in-memory buckets.
Degradations to understand:
- API and provider rate limits are per instance, so cluster-wide limits can be exceeded by up to `instances x limit` (provider may rate-limit us: watch `RATE_LIMITED`).
- Idempotency lookups hit the DB (more load).
- Replay guard for callbacks relies on the `replay_guard` table.

## Diagnose
1. `redis-cli -h <host> ping` (not executed here), cluster/sentinel status, memory/eviction (`INFO memory`), network.
2. Confirm fallback active: metric increments, health indicator state (see source).
3. Check DB load and `hikaricp_connections_pending` because DB absorbs more work.

## Mitigate
- Restore Redis or fail over replica.
- If provider limits are at risk: temporarily lower `messaging.providers.<id>.rate-per-sec` (divide by instance count) or reduce replicas of consumers.
- Do not disable idempotency.

## Recover
Automatic return when Redis is healthy; token buckets start full or empty depending on implementation (see source); watch for burst on recovery.

## Verify
`messaging.redis.fallback` stops increasing; DB load normal; no duplicate messages created (query `message_request` duplicates by `(tenant_id, idempotency_key)` cannot exist: unique).

## Prevent
Redis HA, memory policy `noeviction` for rate-limit keys or isolate them, alerts, DB capacity headroom for fallback.

Proof: `FailureRecoveryIT#scenario07_redisOutage_fallsBackToDb_noDuplicates`.
