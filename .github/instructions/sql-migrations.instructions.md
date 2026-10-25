---
applyTo: "backend/src/main/resources/db/**/*.sql"
---
# SQL migration rules

Full standard: `ai-engineering/standards/db-migrations.md`; rationale `docs/database/schema-evolution.md`.

- Name: `V<n>__snake_case.sql`, next integer after the highest existing; never edit a merged migration.
- Portable SQL for H2 (`MODE=PostgreSQL`) and PostgreSQL: no partial indexes, JSONB, `ON CONFLICT`, `CREATE EXTENSION`, vendor hints. Types: `VARCHAR(n)`, `BIGINT`, `INT`, `BOOLEAN`, `TIMESTAMP` (UTC).
- Expand-only per release: add nullable columns/tables/indexes; no drop/rename/type change in the same release that stops using it.
- Explicit constraint and index names with prefixes `pk_`, `uq_`, `ix_`, `fk_`, at most 30 characters (Oracle compatible).
- Tenant-owned tables carry `tenant_id`; concurrently updated tables carry `version BIGINT`.
- Every new query gets an index decision; remember `message_request` already has six indexes and statuses change about five times per message.
- No large data backfills inside DDL migrations.
- Update `db/oracle` reference DDL, `docs/database/data-model.md` and `docs/architecture/er-diagram.md`.
- Seed data only in the profile-scoped seed location (`local`, `test`), never in prod migrations; synthetic values only.
- Add a retention rule for any new high-volume table.
