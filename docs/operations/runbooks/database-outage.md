# Runbook: Database Outage or Pool Exhaustion

## Symptoms and alerts
`HikariPoolSaturated`, readiness DOWN, 503s on POST /messages, `hikaricp_connections_timeout_total` rising, outbox publisher and consumer errors in logs.

## Impact
New submissions fail closed with 503 (no 202 without commit). Dispatch stalls. Provider calls already in progress may complete without a recorded outcome (becomes `DELIVERY_UNKNOWN` after recovery via sweeper). Callbacks fail (providers retry).

## Diagnose
1. Is the DB down (connection refused) or slow (pool exhausted, locks)? Check `hikaricp_connections_active/pending/acquire_seconds`.
2. Pool exhaustion cause: long transactions (one holding a connection across a call), lock waits, slow query, connection leak (`leak-detection-threshold` logs).
3. PostgreSQL: `SELECT pid, state, wait_event_type, now()-xact_start AS age, left(query,80) FROM pg_stat_activity ORDER BY xact_start NULLS LAST;` and blocking locks via `pg_locks`. (Not executed here.)
4. Thread dump of app: `jcmd <pid> Thread.print` to see threads waiting in `HikariPool.getConnection`.
5. Recent deploy or migration? (long DDL lock) `schema-evolution.md`.

## Mitigate
- Down: fail over/restore DB (platform DBA procedure); the app reconnects (Hikari `connection-test`/`max-lifetime`).
- Locks: terminate the blocking session (`SELECT pg_terminate_backend(pid)`), after confirming it is not a legitimate migration.
- Leak/slow query: roll back release, kill offending query, add missing index (`query-plans-and-indexes.md`).
- Do not raise pool size blindly (shifts overload to DB; check `max_connections`).
- Clients: should retry with the same `Idempotency-Key`.

## Recover
1. Outbox publisher resumes automatically (pending `NEW` rows).
2. `StuckMessageSweeper` moves stale `PROCESSING` to `DELIVERY_UNKNOWN`; reconciliation proceeds.
3. Check outbox backlog age and queue depth, `provider-outage.md` style verification.

## Verify
Readiness UP, pending connections 0, submission success restored, oldest outbox `NEW` age small, no duplicate sends (compare provider calls to attempts for the window).

## Prevent
HA database, connection timeout short (fail fast), alerts on pending connections, statement timeouts, load tests, separate pool for batch.

Proof: `FailureRecoveryIT#scenario06_databaseOutage_submissionFailsClosed_thenRecovers`.
