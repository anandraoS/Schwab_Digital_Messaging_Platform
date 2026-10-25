# Databases: Relational Fundamentals, Oracle Versus PostgreSQL, and MongoDB

A reference chapter for database questions in a senior Java full-stack interview. The reference implementation uses PostgreSQL 16 as the primary store (ADR-0005), H2 in `MODE=PostgreSQL` for local and test runs, MongoDB only for provider diagnostics, and ships an Oracle mapping as documentation and reference DDL (CONTRACT section 1). The Oracle DDL and mapping were written as reference material; nothing here claims they were executed against an Oracle instance, and real PostgreSQL, MongoDB and Oracle were not run in the authoring environment. SQL below is illustrative.

## Kid-friendly analogies

| Concept | Analogy |
|---|---|
| Index | The index at the back of a book. Finding "dragons" by flipping to the index is quick; reading every page is a full table scan. But every new page you add means updating the index too. |
| Query plan | A map app showing the route it picked. Sometimes it picks the highway (index), sometimes the back streets (scan). `EXPLAIN` shows the chosen route and why it seemed cheapest. |
| Isolation level | Rules about peeking at other kids' work in progress. Strict rules mean fewer surprises but more waiting. |
| Deadlock | Two kids each hold one scissors and one glue stick, each waiting for the other's item. A teacher (the database) picks one kid to give up. |
| Offset pagination | Skipping 10,000 pages by counting each one every time. Keyset pagination is a bookmark. |
| Partitioning | Splitting a huge photo album into one binder per month; to find June, open only June's binder. |
| Document database | A folder where each child keeps everything about themselves in one envelope, instead of in separate cabinets for names, addresses and hobbies. |

## Indexes

B-tree indexes are the default in PostgreSQL, Oracle and most relational engines. They support equality, range and ordering.

| Principle | Detail |
|---|---|
| Composite index order matters | Leftmost-prefix rule: an index on `(tenant_id, status, created_at)` serves filters on `tenant_id`, or `tenant_id` plus `status`, but not on `status` alone |
| Equality columns first, then range or sort column | Lets the index deliver rows already in order |
| Covering index | Index contains all selected columns so the table is not touched (PostgreSQL `INCLUDE`, Oracle index-only access when all columns are in the index) |
| Selectivity | Low-selectivity columns (a boolean) are poor leading columns |
| Cost | Every index slows writes and uses storage; remove unused ones |
| Functions defeat indexes | `WHERE lower(email) = ?` needs a function-based or expression index |
| Implicit conversions defeat indexes | Comparing a number column to a string, or passing a `VARCHAR` where Oracle expects `NVARCHAR`, can disable use |
| Foreign keys | PostgreSQL does not index foreign key columns automatically; index them for joins and cascading deletes |

The contract's `message_request` indexes (CONTRACT section 4) show these ideas: `(tenant_id, status, created_at DESC)` for the console list; `(recipient_id, created_at)` for a recipient history; `(status, next_attempt_at)` and `(status, scheduled_at)` for pollers that look for due work; `(correlation_id)` for support lookups; and `(bulk_job_id)`. Unique constraints do double duty as correctness guards: `UNIQUE(tenant_id, idempotency_key)`, `UNIQUE(provider_id, provider_event_id)` on `delivery_event`, and `processed_message.dispatch_id` give the database the final say in races.

Note the contract forbids partial indexes and PostgreSQL-only syntax so that the same Flyway migrations run on H2 and PostgreSQL (CONTRACT section 1). A partial index such as `WHERE status = 'NEW'` would suit an outbox table well in PostgreSQL, and its absence is a deliberate portability tradeoff. Oracle, for reference, can emulate partial indexing with function-based indexes that return NULL for unwanted rows, since Oracle B-tree indexes do not store entirely-NULL keys.

<!-- in-project:d05 -->
> [!NOTE] In this project
> **File:** `backend/src/main/resources/db/migration/V3__messages.sql`
>
> Equality columns first, then the range or sort column. Because every list query is tenant-scoped, `tenant_id` leads the list index.
>
> ```sql
> CREATE INDEX idx_msg_tenant_status_created ON message_request (tenant_id, status, created_at DESC);
> CREATE INDEX idx_msg_recipient_created      ON message_request (recipient_id, created_at);
> CREATE INDEX idx_msg_status_next_attempt    ON message_request (status, next_attempt_at);
> CREATE INDEX idx_msg_status_scheduled       ON message_request (status, scheduled_at);
> ```


## Query plans

```sql
-- PostgreSQL
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, status FROM message_request
WHERE tenant_id = 'tenant-retail' AND status = 'FAILED'
ORDER BY created_at DESC FETCH FIRST 50 ROWS ONLY;

-- Oracle
EXPLAIN PLAN FOR SELECT ...;
SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY);
-- or, with actual row counts:
SELECT /*+ GATHER_PLAN_STATISTICS */ ...;
SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(FORMAT => 'ALLSTATS LAST'));
```

What to look for:

| Plan element | Meaning | Concern |
|---|---|---|
| Seq scan or Oracle `TABLE ACCESS FULL` | Reads the whole table | Fine for small tables or most of the table; bad for selective filters |
| Index scan, index range scan | Uses an index | Check that it matches the filter and the order |
| Nested loop, hash join, merge join | Join algorithms | Nested loop on a large outer set with no inner index is slow |
| Sort | Explicit sort step | Avoidable if an index provides order |
| Estimated versus actual rows | Cardinality estimate quality | Large gaps mean stale statistics; run `ANALYZE` (PostgreSQL) or gather statistics (Oracle) |

Do not tune by guessing: capture the plan, compare estimates to actuals, then add or adjust an index, rewrite the query, or fix statistics. Bind variable peeking and plan instability are classic Oracle topics (adaptive cursor sharing, SQL plan baselines); PostgreSQL generic versus custom plans for prepared statements are the analogue.

## Transactions and isolation levels

| Level | Dirty read | Non-repeatable read | Phantom | PostgreSQL | Oracle |
|---|---|---|---|---|---|
| READ UNCOMMITTED | Allowed by standard | Yes | Yes | Behaves as READ COMMITTED | Not supported |
| READ COMMITTED | No | Yes | Yes | Default; each statement sees a new snapshot | Default; statement-level read consistency |
| REPEATABLE READ | No | No | Standard allows; PostgreSQL prevents | Snapshot isolation for the whole transaction | Not offered as such (`READ ONLY` transactions give a snapshot) |
| SERIALIZABLE | No | No | No | True serializable (SSI); may raise serialisation failures to retry | Snapshot-based, not fully serializable; raises ORA-08177 on conflicts |

Both engines use multiversion concurrency control, so readers do not block writers. Under READ COMMITTED a lost-update race is still possible with read-modify-write in application code; guard with a version column (optimistic), a locking read (`SELECT ... FOR UPDATE`), or a single atomic statement. The contract favours atomic conditional updates for state changes (`UPDATE ... WHERE status = ?`, CONTRACT sections 4 and 6) and `@Version` for user edits (CONTRACT section 5), which work on every isolation level and engine.

`SELECT ... FOR UPDATE SKIP LOCKED` lets workers claim different rows without waiting, and is supported by both PostgreSQL and Oracle; confirm behaviour in H2 before relying on it in tests. The contract instead uses compare-and-set claims for the outbox and pollers, which is portable.

## Deadlocks

A deadlock is a cycle of lock waits. The database detects it and aborts one transaction (PostgreSQL error `40P01`, Oracle `ORA-00060`). Prevention:

- Acquire locks in a consistent order (for example update rows sorted by id).
- Keep transactions short; no remote calls or user think-time inside them.
- Use the lowest suitable isolation and narrow `WHERE` clauses so fewer rows lock.
- Index foreign keys: in Oracle an unindexed foreign key can cause table-level locks on parent changes, a classic cause of surprising lock contention.
- Retry the aborted transaction with backoff, since a deadlock victim is expected behaviour, not a bug.

Diagnose via the server log (PostgreSQL logs the cycle), `pg_locks` joined to `pg_stat_activity`, or Oracle `V$LOCK`, `V$SESSION` and the trace file for ORA-00060. Link it to the application: connection pool exhaustion often presents as "the database is slow" when the real cause is long transactions holding connections (see the Spring chapter on `REQUIRES_NEW`).

## Pagination: offset versus keyset

```sql
-- Offset: cost grows with the offset
SELECT id, created_at FROM message_request
WHERE tenant_id = :t ORDER BY created_at DESC, id DESC
OFFSET 10000 ROWS FETCH NEXT 50 ROWS ONLY;

-- Keyset: seek using the last row of the previous page
SELECT id, created_at FROM message_request
WHERE tenant_id = :t
  AND (created_at < :lastCreatedAt OR (created_at = :lastCreatedAt AND id < :lastId))
ORDER BY created_at DESC, id DESC
FETCH NEXT 50 ROWS ONLY;
```

`OFFSET ... FETCH NEXT` is standard SQL supported by PostgreSQL and Oracle 12c and later (older Oracle needed `ROWNUM` wrappers). Keyset needs a deterministic order with a unique tiebreaker (`id`), plus an index that matches the sort (`(tenant_id, created_at DESC, id DESC)`). Return an opaque cursor token rather than raw values. Offset lets clients jump to page N and show total counts; keyset does not, and counting large tables is itself expensive, so consider approximate counts or omit totals on big lists. The contract's `PageResponse` includes `totalElements` and caps page size at 200 (CONTRACT section 5), which suits operator screens; for event feeds such as delivery events and audit events, keyset is the scalable option.

## Partitioning and retention

Partitioning splits a table into pieces by range (date), list or hash.

| Benefit | Detail |
|---|---|
| Partition pruning | Queries filtered on the partition key touch only relevant partitions |
| Cheap retention | Drop or detach an old partition instead of a huge `DELETE` (no row-by-row undo and bloat) |
| Maintenance | Index rebuilds and vacuum or statistics per partition |

PostgreSQL has declarative partitioning; Oracle has a mature Partitioning option (a separately licensed feature on some editions, which is itself an interview-worthy cost point) with interval partitioning that creates partitions automatically. A primary or unique key generally must include the partition key, which conflicts with a single-column UUID primary key. The contract defines retention settings (`messaging.retention.{message-days,audit-days,event-days}`) and a `RetentionJob` (CONTRACT sections 2 and 9). A batch delete job is the portable baseline; partition dropping is the optimisation for high volume, and it would be a deliberate extension because the contract's portable-migration rule favours plain SQL. Keep audit and compliance retention requirements separate from operational data retention, and remember that deletion must also reach backups and derived stores per policy.

## Oracle specifics and differences from PostgreSQL

CONTRACT section 1 states the Oracle mapping is documented in `docs/database/oracle-mapping.md` with reference DDL under `backend/src/main/resources/db/oracle/`. The following are general, well-known differences for discussion.

| Topic | PostgreSQL | Oracle |
|---|---|---|
| Auto ids | `GENERATED ... AS IDENTITY`, `SERIAL`, sequences | Sequences, and identity columns from 12c (backed by sequences) |
| Sequence use | `nextval('seq')` | `seq.NEXTVAL`; allocate in blocks with `INCREMENT BY` matching Hibernate's pooled optimiser |
| Upsert | `INSERT ... ON CONFLICT` | `MERGE INTO ... USING ... WHEN MATCHED / NOT MATCHED` |
| Empty string | Distinct from NULL | `''` is stored as NULL, which surprises application logic and constraints |
| Strings | `VARCHAR(n)`, `TEXT` | `VARCHAR2(n)` (byte or char semantics; check `NLS_LENGTH_SEMANTICS`), `CLOB` for large text; classic limit 4000 bytes for `VARCHAR2` unless extended |
| Booleans | `BOOLEAN` | No SQL `BOOLEAN` column type before 23ai; use `NUMBER(1)` or `CHAR(1)` |
| Timestamps | `TIMESTAMP`, `TIMESTAMPTZ` | `TIMESTAMP`, `TIMESTAMP WITH TIME ZONE`; `DATE` includes a time component |
| Limit rows | `LIMIT` or `FETCH FIRST` | `FETCH FIRST` (12c and later), older `ROWNUM` |
| DDL transactions | DDL is transactional | DDL auto-commits |
| Dual table | Not needed | `SELECT ... FROM DUAL` |
| JSON | `JSONB` with GIN indexes | JSON in `VARCHAR2`/`CLOB`/`JSON` type (version dependent) with JSON search indexes |
| Identifier length | 63 bytes | 30 bytes before 12.2, 128 after |
| Case | Unquoted identifiers fold to lower case | Fold to upper case |
| Read consistency | MVCC with heap and vacuum | MVCC with undo segments (`ORA-01555` snapshot too old) |
| Locks | Row locks on tuples | Row locks stored in the block; no lock escalation |

### MERGE example

```sql
MERGE INTO replay_guard g
USING (SELECT :eventId AS event_id FROM dual) s
ON (g.event_id = s.event_id)
WHEN NOT MATCHED THEN
  INSERT (event_id, seen_at) VALUES (s.event_id, SYSTIMESTAMP);
```

Equivalent in PostgreSQL: `INSERT ... ON CONFLICT (event_id) DO NOTHING`. The contract forbids `ON CONFLICT` in the shared Flyway migrations because H2 portability is required (CONTRACT section 1), so the application relies on an insert that may fail with a unique violation, handled in code, or on dialect-specific repositories for the Oracle variant. Be careful: `MERGE` is not inherently safe against concurrent inserts of the same key; two sessions can both see "not matched" and one then fails with a unique violation, so the constraint remains the real guard.

### Oracle sequences and Hibernate

Hibernate's `SequenceStyleGenerator` with `allocationSize` equal to the sequence's `INCREMENT BY` gives batch-friendly ids with fewer round trips. The contract's primary keys are application-generated UUID strings, which avoids the choice, at the price of larger keys and random insert locations in the index (page splits); time-ordered UUIDs reduce that.

### Also worth knowing

Connection management uses the pool (HikariCP) either way. Oracle bind variables avoid hard parses and are required for good plan sharing, so use prepared statements, which JPA does. Oracle RAC, Data Guard, and Exadata are operational topics; know them at the level of "high availability and read scaling exist, with licensing implications". PostgreSQL equivalents are streaming replication, Patroni-style failover, and read replicas. Be honest in an interview about what you have operated versus read about.

## Spring Batch job repository tables (brief)

Spring Batch 5 persists job metadata in a JDBC job repository so jobs are restartable (CONTRACT sections 1 and 7; created by migration `V100__spring_batch.sql`, CONTRACT section 4).

| Table | Purpose |
|---|---|
| `BATCH_JOB_INSTANCE` | One logical job run per unique name plus identifying parameters |
| `BATCH_JOB_EXECUTION` and `BATCH_JOB_EXECUTION_PARAMS` | Each attempt to run an instance, with status and parameters |
| `BATCH_STEP_EXECUTION` | Per-step counts (read, write, skip, commit) and status |
| `BATCH_JOB_EXECUTION_CONTEXT`, `BATCH_STEP_EXECUTION_CONTEXT` | Serialized state, including reader position, which enables resume from the last committed chunk |
| `*_SEQ` sequences or tables | Id generation (varies by database) |

Restart relaunches the same identifying parameters and resumes from the saved step context; the contract's `bulk_job` row stores `spring_batch_execution_id` to link domain and framework state (CONTRACT section 4). Operationally: keep the batch tables in the same database for transactional consistency, purge old executions, and make the writer idempotent (the contract uses deterministic keys `bulk-<jobId>-<externalRef>`, CONTRACT section 7) because a chunk can be re-executed after a crash.

## MongoDB

### Role in this design

The contract uses MongoDB only for the `provider_diagnostics` collection: provider diagnostics and the raw callback payload archive, with writes best-effort so a Mongo failure never fails message processing (metric `messaging.diagnostics.write.failures`, CONTRACT sections 1 and 8). Local and test use an in-memory Mongo-compatible server (`mongo-java-server`), which implements a subset of the MongoDB wire protocol, so real MongoDB behaviour (index options, aggregation details, TTL monitor timing) is not proven by it.

Why Mongo fits that slice of data: provider payloads are heterogeneous, schema varies per provider, they are written once and read rarely, and they do not take part in transactions with the core state. Why it does not belong in the core: message state needs relational constraints, multi-row transactions and the unique guards described above.

### Modelling

| Principle | Detail |
|---|---|
| Model for access patterns | Embed data read together; reference data that grows unboundedly or is shared |
| Embed versus reference | Embedding gives atomic single-document updates; the 16 MB document limit and unbounded arrays are the risks |
| Avoid unbounded arrays | Use a separate collection with a reference |
| Schema flexibility is not schema absence | Add schema validation (`$jsonSchema`) for key fields and a `schemaVersion` field |
| Single-document operations are atomic | Multi-document transactions exist but cost performance and need replica sets |

```json
{
  "_id": "diag-8841",
  "messageId": "9f2c1d7e-...",
  "providerId": "sms-acme",
  "direction": "CALLBACK",
  "receivedAt": { "$date": "2026-11-01T14:00:02Z" },
  "payload": { "status": "delivered", "code": "000" },
  "schemaVersion": 1
}
```

Security note: raw provider payloads can contain personal data (phone numbers) or tokens. Redact or hash sensitive fields before archiving, per the platform's redaction component (`Redaction`, CONTRACT section 2), and apply retention.

### Indexes and TTL

```javascript
db.provider_diagnostics.createIndex({ messageId: 1, receivedAt: -1 })
db.provider_diagnostics.createIndex({ receivedAt: 1 }, { expireAfterSeconds: 2592000 })
```

Compound index order follows the same leftmost rules as relational. A TTL index on a date field removes documents after the given seconds; a background task runs periodically (about once a minute), so expiry is approximate, not instantaneous. Use `explain("executionStats")` to compare `totalDocsExamined` with `nReturned`; a collection scan (`COLLSCAN`) on a large collection is the red flag. Create a unique index for any natural dedupe key, and remember that sharded collections need a shard key chosen for even distribution and query targeting.

### Write and read concerns

Write concern (`w: 1` versus `w: majority`) and read concern/preference control durability and staleness. For best-effort diagnostics, a lower write concern with a short timeout is acceptable because loss is tolerable by design; for anything authoritative it is not.

## Choosing stores

| Need | Choice | Reasoning |
|---|---|---|
| Authoritative state, constraints, transactions, reporting joins | PostgreSQL (or Oracle where mandated) | ACID, unique constraints, mature tooling |
| Raw heterogeneous payloads, write-heavy, read-rare | MongoDB | Flexible schema, TTL cleanup, off the critical path |
| Hot counters, idempotency fast path, rate limiting, short-lived locks | Redis | Sub-millisecond, atomic scripts, expiry; not the source of truth (the contract falls back to the database when Redis is down) |
| Work distribution and delays | RabbitMQ (ADR-0001) | Queue semantics, priorities, TTL, DLX |
| Replayable event log, analytics | Kafka | Retention and offsets |
| Full-text search | Search engine | Not a relational strength |

State the principle: one authoritative store per fact, other stores are derived or best-effort, and every added technology must justify its operational cost. The contract follows it: PostgreSQL is authoritative, Redis accelerates, Mongo archives, and the failure of the latter two degrades gracefully (CONTRACT section 1).

## Worked interview question: "The message list is slow for the support team"

Approach: reproduce with the exact filters, get the plan with `EXPLAIN (ANALYZE, BUFFERS)`, check whether the index `(tenant_id, status, created_at DESC)` is used and whether the sort is satisfied by it; check estimated versus actual rows and statistics freshness; check that the ORM is not issuing N+1 queries; check deep offset pagination and switch to keyset if needed; avoid a `count(*)` on every page; consider a covering index or projection to avoid fetching wide rows; and only then consider partitioning or caching. Measure after each change.

## Key takeaways

- Index design follows the query: equality columns first, then range or sort; composite order matters; each index costs writes; foreign keys need indexes.
- Read plans with `EXPLAIN ANALYZE` (PostgreSQL) or `DBMS_XPLAN` (Oracle), compare estimated to actual rows, and fix statistics before guessing.
- READ COMMITTED is the default on both engines; use unique constraints, version columns and conditional updates rather than relying on higher isolation.
- Prevent deadlocks with consistent lock order and short transactions, and retry victims.
- Prefer keyset pagination for large or growing data; offset is acceptable for bounded operator views.
- Partitioning gives pruning and cheap retention but constrains keys and has edition and licensing implications in Oracle.
- Know the main Oracle differences: sequences, `MERGE`, empty string equals NULL, `VARCHAR2`/`CLOB`, no `BOOLEAN` before 23ai, auto-committing DDL, and `FETCH FIRST` in modern versions; the shared migrations in this design stay portable by avoiding PostgreSQL-only syntax.
- Spring Batch persists restart state in its job repository tables; make writers idempotent.
- MongoDB suits flexible, write-once diagnostic payloads with TTL expiry, modelled around access patterns; keep authoritative state in the relational store.
- Choose stores by need, keep one source of truth per fact, and let secondary stores fail soft.
