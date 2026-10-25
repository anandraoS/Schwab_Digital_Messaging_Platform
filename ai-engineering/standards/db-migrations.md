# Database Migration Standards

Full rationale: `docs/database/schema-evolution.md`. Applies to `backend/src/main/resources/db/**`.

## Rules
1. File naming `V<n>__snake_case_description.sql`, strictly increasing; `V100__spring_batch.sql` reserved; seed under profile-scoped location only.
2. Never edit a merged migration; fix forward.
3. Portable SQL (H2 `MODE=PostgreSQL` and PostgreSQL): no partial indexes, JSONB, `ON CONFLICT`, `RETURNING` reliance, vendor hints, `CREATE EXTENSION`. Types: `VARCHAR(n)`, `BIGINT`, `INT`, `BOOLEAN`, `TIMESTAMP`.
4. Expand-only in a release: add nullable columns/tables/indexes; no drop/rename/type change; contract in a later release.
5. Every new table: `id VARCHAR(36)` pk, `created_at`, `updated_at`, `tenant_id` if tenant-owned, `version BIGINT` if concurrently updated; explicit constraint names (`uq_`, `ix_`, `fk_` prefixes) <= 30 chars (Oracle compatibility).
6. Every new query needs a named index decision (add or justify none) and a note in `docs/database/query-plans-and-indexes.md` if hot.
7. Unique constraints encode business invariants (idempotency, dedupe): prefer constraints over application checks.
8. No data backfill of large tables inside DDL migrations; use chunked jobs.
9. Update `db/oracle` reference DDL and `docs/database/data-model.md`/ER doc in the same PR.
10. Include a migration test or rely on the integration context booting Flyway on H2; for risky changes document expected lock behavior and duration for PostgreSQL.

## Review checklist
- [ ] Rollback-safe for the previous app version?
- [ ] Locks: does it rewrite or block a hot table?
- [ ] Constraint violations possible on existing data? (pre-check query)
- [ ] Index count on `message_request` justified?
- [ ] Oracle: identifier length, boolean, empty string semantics considered?
- [ ] Retention impact (new table needs a purge rule)?

## Template
```sql
-- V<n>__add_<thing>.sql  (expand only)
ALTER TABLE message_request ADD COLUMN expires_reason VARCHAR(64);
CREATE INDEX ix_msg_expires_reason ON message_request (expires_reason);   -- only if a named query needs it
```
