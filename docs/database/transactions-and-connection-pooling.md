# Transaction Isolation and Connection-Pool Sizing

## Isolation
| Operation | Isolation | Technique | Why |
|---|---|---|---|
| Submission | READ COMMITTED (PG default) | unique constraint on idempotency; single tx | uniqueness gives serializability where needed without SERIALIZABLE retries |
| Status transitions | READ COMMITTED | CAS `UPDATE ... WHERE id=? AND status=?` check affected rows == 1, plus `@Version` | cheap, no row locks held beyond the statement |
| Outbox claim | READ COMMITTED | CAS update of a batch of ids (or `FOR UPDATE SKIP LOCKED` as optimization, not on H2 reliably: see source) | multiple instances without double claim |
| Callback apply | READ COMMITTED | insert unique event, CAS | duplicate = unique violation, treated as success |
| Reports/summary | READ COMMITTED (read-only tx) | read replica optional | slight staleness fine |
| Retention deletes | READ COMMITTED | chunked | bounded lock footprint |
Not used: SERIALIZABLE (retry complexity), REPEATABLE READ (PG snapshot could mask CAS expectations; unnecessary). H2 `MODE=PostgreSQL` default is READ COMMITTED; MVCC differences: assert behavior with `*ContainerIT` on real PG (not executed here).

Anti-patterns avoided: holding a transaction across the provider call; `SELECT` then `UPDATE` without CAS (lost update); catching `DataIntegrityViolationException` inside the same transaction and continuing (PG aborts the transaction: use a new transaction or check-then-insert in a savepoint, see source `IdempotencyService`/`ReplayGuard`).

## Why no transaction over the provider call
Provider latency can be seconds; holding a DB connection that long would exhaust the pool (pool size is small) and amplify a provider slowdown into a DB-wide outage. Dispatch uses three short transactions (claim, attempt STARTED, outcome).

## HikariCP sizing
Formula (starting point, then measure): `pool = (core_count * 2) + effective_spindle_count`, but for this app size from concurrency:

Concurrent DB users per instance = API threads actively in DB + consumer workers + schedulers + batch threads, but each holds a connection only during short transactions.
| Source | Concurrency | Connection hold |
|---|---|---|
| Tomcat request threads | e.g. 50 max, ~20 in DB at once at peak | ~5-20 ms |
| Dispatch workers | `dispatch.concurrency` = 8 | 2 short tx per message |
| Schedulers (outbox, poller, sweeper, retention) | ~4 | short |
| Batch | `bulk.concurrency` = 4 | chunk tx up to 1-2 s (500 rows) |

Illustrative sizing: `maximum-pool-size` 20-30 per instance, `minimum-idle` = max (fixed-size pool recommended), `connection-timeout` 2-3 s (fail fast, return 503 rather than queue clients forever), `max-lifetime` below DB/proxy idle kill (e.g. 20 min), `leak-detection-threshold` 10-20 s in non-prod. Total across instances must stay under PG `max_connections` minus headroom: `instances * pool <= ~70% of max_connections` (or use PgBouncer in transaction mode; note prepared statements and session state caveats).

Little's law check: connections needed = throughput (tx/s) x avg hold time (s). 1000 tx/s x 10 ms = 10 connections. If hold time grows 10x (DB slow), required connections 10x: pool saturates, so alert on `hikaricp_connections_pending > 0` sustained and `hikaricp_connections_acquire_seconds` p99.

Separating pools: bulk/batch and retention use the same DB but may use a dedicated small pool (`maximumPoolSize` 4-5) so that bulk jobs cannot starve API connections (option; see source for whether implemented).

## Monitoring
| Metric | Meaning | Action |
|---|---|---|
| `hikaricp_connections_active` | in use | near max = saturation |
| `hikaricp_connections_pending` | waiting threads | >0 sustained = pool or DB slow |
| `hikaricp_connections_acquire_seconds` | wait time | p99 > connection-timeout/2 investigate |
| `hikaricp_connections_timeout_total` | failures | paging alert |
| `hikaricp_connections_usage_seconds` | hold time | spikes = slow SQL or tx held too long |
Diagnosis commands: `docs/operations/performance-investigation-guide.md`.

## Deadlocks and lock waits
Consistent ordering: message row before attempt/event rows; audit inserts last. Unique-violation retries are bounded. PG: `SELECT * FROM pg_locks l JOIN pg_stat_activity a USING (pid) WHERE NOT granted;` to inspect (not executed here).
