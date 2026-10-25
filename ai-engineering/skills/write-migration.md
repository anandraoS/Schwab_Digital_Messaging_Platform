# Skill: Write a Flyway Migration

**When**: schema change. **Load**: `context/db.md`, `standards/db-migrations.md`, `docs/database/schema-evolution.md`.

## Steps
1. Find the highest `V<n>` in `backend/src/main/resources/db/migration`; use next number.
2. Write expand-only portable SQL (no partial index, JSONB, `ON CONFLICT`); explicit constraint names <= 30 chars.
3. For a new query: decide index with the named query; check effect on write amplification of `message_request`.
4. New table: add retention rule and tenant column if tenant-owned.
5. Update `db/oracle` reference DDL; update `docs/database/data-model.md` and `docs/architecture/er-diagram.md`.
6. Update JPA entities/repositories; no N+1; tenant-scoped finders.
7. Run the integration tests (Flyway on H2). Note: PostgreSQL not executed locally unless `-Pcontainers` with Docker.
8. Plan contract step for a later release if replacing something.

## Output
Migration file, entity/repo changes, docs, test evidence.
