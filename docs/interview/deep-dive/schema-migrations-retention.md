# Schema Migrations, Retention and Archival

A database outlives every version of the application that uses it. This chapter covers how to change a schema while old and new application versions run side by side, how to delete data you are no longer allowed or needed to keep, and how to do both without freezing the system. The project examples come from the Flyway migrations and `RetentionJob`; nothing was run against a production-sized database.

## Kid-friendly analogies

| Concept | Analogy |
|---|---|
| Expand and contract | Building a new bridge beside the old one, moving traffic over, then closing the old bridge. You never close a bridge while cars are on it. |
| Backfill | Copying the contact names from the old address book into the new one, a page at a time, so nobody has to stop using the old book. |
| Migration immutability | Once a page is printed in the school yearbook you cannot change it; you can only print an errata page. |
| Retention | Clearing the fridge every Sunday: food that is past its date goes, except what the teacher asked you to keep for the science fair (legal hold). |
| Partition drop | Throwing away a whole old box of papers instead of removing the sheets one at a time. |

## Flyway basics

Flyway runs versioned scripts (`V<n>__description.sql`) in order, records each in `flyway_schema_history` with a checksum, and refuses to start if an applied script was edited. Repeatable scripts (`R__`) rerun when their checksum changes. Principles:

1. Applied migrations are immutable. A mistake is fixed by a new migration (roll forward).
2. One concern per migration, small, reviewed like code.
3. Hibernate only validates (`ddl-auto: validate`); it never creates or alters production tables.
4. The same scripts run in tests (H2 in PostgreSQL mode), so syntax stays portable.
5. Large data changes are separate, chunked, resumable jobs, not part of a DDL migration.

> [!NOTE] In this project
> `backend/src/main/resources/db/migration/` holds `V1__tenant_recipient.sql`, `V2__templates.sql`, `V3__messages.sql`, `V4__bulk_audit_idempotency_outbox.sql`, `V50__dispatch_indexes.sql`, `V100__spring_batch.sql` and `V200__bulk_ledger_legal_hold.sql` (gaps in numbering leave room for ordered insertion by different modules). `application.yml` sets `spring.flyway.enabled: true` and `spring.jpa.hibernate.ddl-auto: validate`. `docs/database/schema-evolution.md` is the written policy. The Oracle reference DDL is kept separately and was not extended for `V200` (see `docs/design/DEVIATIONS.md`).

## Expand, migrate, contract

| Phase | Release | Action | Old code still works? |
|---|---|---|---|
| Expand | N | Add nullable column, new table or new index | Yes |
| Dual-write | N | Application writes old and new | Yes |
| Backfill | N or ops job | Chunked, idempotent update of old rows | Yes |
| Switch reads | N+1 | Application reads the new structure | Yes (data is present) |
| Contract | N+2 | Add `NOT NULL` or constraint, drop the old column | Only after N is retired |

Example 1 (easy): add a nullable `expires_reason` column. Release N writes it, readers tolerate null, no backfill, no contract step.

Example 2 (medium): rename `payload_json` to `variables_json`. Never `RENAME` in place: add the new column, dual-write, backfill in chunks of 1,000 rows with a pause, switch reads, drop the old column one full release later.

Example 3 (hard): add a unique constraint on `(tenant_id, idempotency_key)` to a table that already has data. First run a query that lists duplicates and resolve them, then create a unique index (on PostgreSQL `CREATE UNIQUE INDEX CONCURRENTLY`, outside the Flyway transaction), then attach the constraint using that index.

### Risky operations

| Operation | Risk | Safer approach |
|---|---|---|
| `ADD COLUMN ... NOT NULL DEFAULT` on a large table | Table rewrite or long lock on older engines | Add nullable, backfill, then add the constraint |
| `CREATE INDEX` on a hot table | Blocks writes | PostgreSQL `CONCURRENTLY`; Oracle `ONLINE` |
| Change a column type | Full rewrite | New column, copy, switch |
| Drop a column | Breaks the previous version during a rolling deploy or rollback | Wait for N+2 |
| Long data migration inside Flyway | Holds a lock and the deployment | Separate resumable job |

### Rollback

Database rollback is rolling forward. An application rollback is safe only if the previous version tolerates the new schema, which is exactly what expand-only migrations guarantee. A contract migration is therefore the point of no return and deserves its own release and approval. Combine with feature flags so behaviour can be switched off without a schema change.

## Zero-downtime and the deployment strategies

| Deployment | Schema requirement |
|---|---|
| Rolling (Kubernetes, `cf push --strategy rolling`) | Versions N and N+1 run together: expand-compatible only |
| Blue-green | Both environments share one database: same constraint |
| Canary | As rolling; a rollback must not need a schema rollback |

## Retention

Retention is the rule that data is deleted when its purpose and legal need end. It is both a cost control and a privacy control (data minimisation).

| Data class | Illustrative retention | Reasoning |
|---|---|---|
| Message rows in terminal states | 90 days | Support and dispute window |
| Delivery events | Follows the message | Evidence of delivery |
| Audit events | Longer than the data they describe (for example 400 days) | Proof must outlive the subject |
| Idempotency records | A day or a few days | Clients retry within hours; must exceed the replay horizon |
| Processed-message dedupe rows | About 7 days | Must exceed the longest redelivery or replay delay |
| Outbox rows already sent | About 7 days | Debugging only |
| Diagnostic payloads in Mongo | 30 days via a TTL index | Operator convenience, redacted |

These are illustrative defaults for a reference system, not legal advice; real values are a compliance decision.

Rules that prevent expensive mistakes:
1. Never delete non-terminal messages by age; alert on them instead.
2. Delete children before parents, audit last.
3. Idempotency retention must exceed the replay horizon, or an old retried request could create a new message.
4. Delete in bounded chunks with a pause so locks stay short and the write-ahead log and replication keep up.
5. Honour legal holds in every purge query.
6. Run a dry-run mode first and record counts only (never personal data) in audit.

### Batched delete versus partition drop

| Approach | Strength | Weakness |
|---|---|---|
| Batched `DELETE` by age | Works on any table, no schema change | Slow for billions of rows, bloat and vacuum or undo pressure |
| Time partitioning and `DROP PARTITION` | Instant, no bloat | Partition key must be in unique constraints (PostgreSQL), more operational design; Oracle interval partitioning is the usual answer |
| Archive then delete | Keeps aggregates for reporting | The archive is also personal data unless anonymised |

Example 1: 5 million rows per month with a 90-day window is about 15 million live rows, comfortably handled by batched deletes of 1,000 rows. Example 2: 50 million per month is 150 million live rows and a 50-million-row monthly delete; move to monthly partitions and drop the oldest. Example 3: a legal hold for one customer must exclude that customer's rows from both approaches; with partition drops that usually means copying the held rows out first.

> [!NOTE] In this project
> `scheduler/RetentionJob` runs on `${messaging.retention.cron:0 30 2 * * *}`, has a dry-run mode, purges `message_request`, `delivery_event`, `audit_event`, `idempotency_record`, `processed_message` and `outbox_event` in batches, and uses a `NOT_HELD` predicate against the `legal_hold` table (migration `V200`) in the message and audit purges. Properties live in `RetentionProperties` (`batch-size`, `pause-ms`, `max-batches-per-table`, `dry-run`). Counts are emitted as `messaging.retention.deleted{table}` and audited as `RETENTION_PURGE`. `docs/database/retention-and-archival.md` records the table of defaults and the partitioning path. The job was exercised by tests against H2, not against a production-sized PostgreSQL or Oracle.

## Archival and right to erasure

Archival means exporting what you still need (aggregates by day, tenant, channel and outcome) before deletion. Keep personal data out of the archive unless it carries the same encryption and access controls as production. Right to erasure is different from retention: it is triggered by a person, needs hard anonymisation of address columns, and must keep what the law requires (suppression hashes so the person is not messaged again, anonymised audit records). The reference design lists automated erasure as not implemented (`docs/security/prod-vs-reference.md`).

## Interview Q and A snippets

**Q: How do you rename a column with no downtime?**
A: You do not rename. Add the new column, write both, backfill in chunks, switch reads, and drop the old column one release later. Every step is compatible with the previous application version, so a rollback is safe.

**Q: A purge query has been running for hours and replication is lagging. What now?**
A: Stop it (the job is idempotent and resumable), reduce batch size, add a pause, make sure the predicate uses an index, and move to partition drops if volume demands it.

## Key takeaways

- Migrations are immutable, small and expand-compatible; contract steps are separate, approved releases.
- Rolling back means rolling forward; expand-only schema changes make application rollback safe.
- Retention is a policy with legal holds, chunked deletes and dry runs; partitioning is the scale answer.
- Archive aggregates, not personal data; erasure is a separate, person-triggered process.
