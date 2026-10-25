# Query Plans and Indexes

**Status of the plans below: ILLUSTRATIVE.** They show the shape a planner is expected to choose and are not output from an executed run unless a block is explicitly labelled `EXECUTED (engine, date)`. Executed H2/PostgreSQL plans, if produced later, are to be appended under "Executed plans" with the exact command. No PostgreSQL instance was available during authoring.

## Hot queries
| # | Query intent | Frequency | Path |
|---|---|---|---|
| Q1 | Retry/scheduled poller: due messages | every 200 ms to 1 s | `status`, `next_attempt_at` |
| Q2 | Outbox claim | every 200 ms | `outbox_event` NEW |
| Q3 | Message list by tenant+status, newest first, paged | UI | `(tenant_id, status, created_at DESC)` |
| Q4 | Recipient history | support | `(recipient_id, created_at)` |
| Q5 | Message history timeline | UI | audit + attempts + events by message id |
| Q6 | Sweeper: PROCESSING with stale lock | every N s | `status` (+ `locked_until`, see source) |
| Q7 | Ops summary counts last 24h | dashboard (cache!) | tenant, created_at, status |

## Q1: due retries (illustrative)
```sql
SELECT id FROM message_request
WHERE status = 'RETRY_PENDING' AND next_attempt_at <= :now
ORDER BY next_attempt_at
FETCH FIRST 200 ROWS ONLY;   -- LIMIT 200 on PG/H2
```
Illustrative plan (PostgreSQL):
```
Limit
  -> Index Scan using ix_msg_status_next_attempt on message_request
       Index Cond: ((status = 'RETRY_PENDING') AND (next_attempt_at <= $1))
```
Trade-off: non-partial index `(status, next_attempt_at)` includes all terminal rows (95%+ of the table). Selectivity on `status='RETRY_PENDING'` is high so it works, but the index is large. PostgreSQL partial index (`WHERE status IN ('RETRY_PENDING','VALIDATED')`) would be smaller but is banned by the portable-SQL rule; Oracle can emulate via function-based index returning NULL for terminal statuses. Revisit if poller latency grows. Mitigation: purge terminal rows by retention.

## Q3: tenant list (illustrative)
```sql
SELECT ... FROM message_request
WHERE tenant_id = :t AND status = :s
ORDER BY created_at DESC LIMIT 50 OFFSET 0;
```
```
Limit -> Index Scan Backward using ix_msg_tenant_status_created
```
Matches index order, no sort. Without `status` filter the planner uses the index prefix `tenant_id` then a sort (or a different index): add `(tenant_id, created_at DESC)` only if that query dominates (cost: write amplification). `OFFSET` deep paging cost grows linearly: recommend keyset pagination for exports (see oracle-mapping).

## Q5: history, avoiding N+1
Bad: load message, then `attempts` lazily, then each event lazily. Good: three indexed queries by message id (or one `UNION ALL` projection) merged in memory:
```sql
SELECT 'AUDIT' k, occurred_at ts, action label FROM audit_event WHERE entity_type='MESSAGE' AND entity_id=:id
UNION ALL SELECT 'ATTEMPT', started_at, outcome FROM message_attempt WHERE message_id=:id
UNION ALL SELECT 'EVENT', occurred_at, type FROM delivery_event WHERE message_id=:id
ORDER BY ts;
```
Needs `delivery_event(message_id)` index (not in CONTRACT list; add in a migration, see source). List endpoints use `@EntityGraph`/projections (no lazy loading in loops); verify with Hibernate statistics in a test asserting statement count.

## Index trade-offs
| Index | Benefit | Cost |
|---|---|---|
| Unique `(tenant_id, idempotency_key)` | correctness + lookup | insert cost, required |
| `(tenant_id, status, created_at DESC)` | main list | updated on every status change (status is in the key): high churn on hot rows; HOT updates impossible when indexed column changes (PostgreSQL), so expect bloat and tune autovacuum |
| `(status, next_attempt_at)` | pollers | same churn |
| `(status, scheduled_at)` | scheduled poller | same churn |
| `(correlation_id)` | support lookups | small |
| `audit_event` indexes | timelines | audit table grows fastest; partition by month in prod |
Rule of thumb adopted: each additional index on `message_request` needs a named query and an owner; removal candidates are reviewed quarterly using `pg_stat_user_indexes` (`idx_scan = 0`).

## Verification commands (to run when PostgreSQL available; NOT executed here)
```sql
EXPLAIN (ANALYZE, BUFFERS) <query>;
SELECT relname, idx_scan FROM pg_stat_user_indexes ORDER BY idx_scan;
SELECT n_dead_tup, last_autovacuum FROM pg_stat_user_tables WHERE relname='message_request';
```
H2: `EXPLAIN ANALYZE SELECT ...` (plan text differs; use only to catch full scans).

## Executed plans
None recorded at the time of writing.
