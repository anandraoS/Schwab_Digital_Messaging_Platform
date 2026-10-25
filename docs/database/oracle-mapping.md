# Oracle Mapping

Reference DDL lives in `backend/src/main/resources/db/oracle/` (reference only; **not executed** here, no Oracle instance available). This document lists the mechanical changes from the portable PostgreSQL/H2 schema and the semantic differences to watch.

## Type mapping
| Portable (PG/H2) | Oracle 19c+ | Notes |
|---|---|---|
| `VARCHAR(36)` id | `VARCHAR2(36 CHAR)` or `RAW(16)` | keep VARCHAR2 for simplicity; RAW(16) halves index size (needs converter) |
| `VARCHAR(n)` | `VARCHAR2(n CHAR)` | Oracle treats `''` as NULL: never rely on empty-string vs null distinction; app normalizes blank to NULL |
| `VARCHAR(4000)` JSON text | `VARCHAR2(4000 CHAR)`; > 4000 bytes use `CLOB` | with `MAX_STRING_SIZE=EXTENDED` 32767 possible. For validated JSON use `CHECK (col IS JSON)` (Oracle-only) |
| long text (`response_body`) | `CLOB` | avoid selecting CLOBs in list queries; mark `LAZY` in JPA |
| `BIGINT` | `NUMBER(19)` | |
| `INT` | `NUMBER(10)` | |
| `BOOLEAN` | `NUMBER(1)` with `CHECK (x IN (0,1))` | Hibernate maps via `@Convert` or dialect (Oracle 23ai has native BOOLEAN) |
| `TIMESTAMP` (UTC) | `TIMESTAMP(6)` or `TIMESTAMP(6) WITH TIME ZONE` | store UTC; use `WITH TIME ZONE` only if you need the original offset (not needed: user time zones live in `recipient.time_zone`). Avoid `TIMESTAMP WITH LOCAL TIME ZONE` surprises (session TZ) unless deliberate. Set `-Doracle.jdbc.timezoneAsRegion=false` and `hibernate.jdbc.time_zone=UTC` |
| `now()` / `CURRENT_TIMESTAMP` | `SYSTIMESTAMP` | app passes `Clock` values instead: preferred |
| `DESC` index column | works, creates function-based index | fine |

## Identity and sequences
Ids are application-generated UUID strings, so no identity columns are needed. For `message_attempt` numbering and any surrogate numeric keys, use `CREATE SEQUENCE ... CACHE 1000` (not `GENERATED ALWAYS AS IDENTITY` if portability to 11g-style matters; identity is fine on 12c+). Hibernate: `@SequenceGenerator(allocationSize=50)` must equal sequence `INCREMENT BY`.

## Upsert: MERGE vs portable pattern
Portable code uses "insert, catch unique violation, then update" or CAS `UPDATE ... WHERE`. Oracle option for hot paths:
```sql
MERGE INTO replay_guard t
USING (SELECT :event_id AS event_id FROM dual) s ON (t.event_id = s.event_id)
WHEN NOT MATCHED THEN INSERT (event_id, seen_at) VALUES (s.event_id, SYSTIMESTAMP);
```
PostgreSQL equivalent (not used because of portability rule) is `INSERT ... ON CONFLICT DO NOTHING`. Prefer the unique-violation approach in the app (`DataIntegrityViolationException`): identical semantics on both engines; MERGE under concurrency can still raise ORA-00001 and must be retried.

## Pagination
PostgreSQL/H2: `LIMIT :size OFFSET :offset`. Oracle 12c+: `OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY` (Hibernate generates it via `OracleDialect`). Keyset pagination (`WHERE (created_at, id) < (:ts, :id) ORDER BY created_at DESC, id DESC FETCH FIRST :n ROWS ONLY`) is preferred for deep paging; Oracle needs the expanded form `created_at < :ts OR (created_at = :ts AND id < :id)`. Top-N: `FETCH FIRST 1 ROW ONLY`.

## Concurrency primitives
| Need | PostgreSQL | Oracle |
|---|---|---|
| Claim batch without blocking | `SELECT ... FOR UPDATE SKIP LOCKED` | same syntax (11g+, officially documented 12c+); our CAS `UPDATE` pattern avoids both |
| Optimistic lock | `version` column | same |
| Default isolation | READ COMMITTED (locking reads) | READ COMMITTED with statement-level snapshot (read consistency); `SERIALIZABLE` raises ORA-08177 |

## Hints and plans
Do not embed hints in portable code. On Oracle, if the optimizer picks poorly for the retry poller, prefer fixing statistics (`DBMS_STATS`), adding histograms on `status` (skewed), or SQL Plan Baselines over `/*+ INDEX(...) */`. Bind peeking plus skewed `status` is a classic issue: terminal statuses dominate (>95% of rows). If hints are unavoidable they go in a vendor-specific repository override, not in shared JPQL.

## Partitioning (Oracle Enterprise option)
| Table | Partitioning | Benefit |
|---|---|---|
| `message_request`, `message_attempt`, `delivery_event`, `audit_event` | interval partition on `created_at`/`occurred_at` by day or month | retention = `DROP PARTITION` instead of mass DELETE; partition pruning for date filters |
| `outbox_event`, `processed_message`, `replay_guard` | short-lived: interval partition by day | cheap purge |
Local indexes are preferred; global unique indexes (e.g., `UNIQUE(tenant_id, idempotency_key)`) must include the partition key or be global (global indexes need maintenance on partition drop: `UPDATE GLOBAL INDEXES`). PostgreSQL can do the same with declarative partitioning, but unique constraints must then include the partition key: a real design constraint if partitioning is adopted (idempotency lookups would need the date or a separate small dedupe table).

## Other differences
| Topic | PostgreSQL | Oracle |
|---|---|---|
| Identifiers | lower case | upper case unless quoted; keep names <= 30 chars for 12.1 compat (128 on 12.2+) |
| DDL transactionality | transactional | DDL auto-commits; Flyway migrations must be small and idempotent |
| Boolean/empty string | real boolean; '' != NULL | emulated; '' = NULL |
| Case-insensitive search | `ILIKE`, `citext` | `UPPER(col) LIKE`, function-based index |
| Locking readers | readers don't block | readers don't block |
| Sequences/serial | identity/sequence | sequence/identity |
| JDBC driver / pool | PG driver | ojdbc11, set `oracle.jdbc.fanEnabled`, UCP optional; Hikari fine |
| `LIMIT` in subquery | allowed | use `FETCH FIRST` in subquery |
| String aggregation | `string_agg` | `LISTAGG` |

## Migration checklist (PG to Oracle)
1. Translate DDL using the table above; run the reference DDL.
2. Switch Hibernate dialect, driver, `hibernate.jdbc.time_zone=UTC`.
3. Re-run integration tests against Oracle (Testcontainers `gvenzl/oracle-free` is an option; **not executed here**).
4. Compare plans for the five hot queries (`query-plans-and-indexes.md`).
5. Validate blank-string handling for optional columns.
