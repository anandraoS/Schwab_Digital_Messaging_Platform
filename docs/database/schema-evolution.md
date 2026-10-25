# Schema Evolution (expand / contract)

Flyway (`backend/src/main/resources/db/migration`, `V<n>__description.sql`). Rolling deployments mean version N and N+1 of the app run against the same schema simultaneously, so every migration must be compatible with both.

## Rules
1. Migrations are immutable after merge. Fix forward with a new version.
2. Portable SQL only (H2 PostgreSQL mode + PostgreSQL; Oracle reference DDL updated in the same PR).
3. One concern per migration; small; no data backfill of large tables inside a DDL migration.
4. Seed data only via profile-scoped location (`local`, `test`).
5. Never `DROP`/rename in the same release that stops using the column.

## Expand / migrate / contract
| Phase | Release | Action | Compatible with old code? |
|---|---|---|---|
| Expand | N | Add nullable column / new table / new index (create index concurrently on PG in prod via a manual runbook step if table is large) | yes |
| Dual-write | N | App writes old and new | yes |
| Backfill | N or ops job | Chunked update (`WHERE new_col IS NULL`, 1000 rows, sleep), idempotent, resumable | yes |
| Switch reads | N+1 | App reads new | old N still works because data present |
| Contract | N+2 | Add `NOT NULL`/constraint after backfill verified, drop old column | only once N is retired |

## Worked example: add `message_request.expires_reason`
1. `V12__add_expires_reason.sql`: `ALTER TABLE message_request ADD COLUMN expires_reason VARCHAR(64);` (nullable, no default) (example number illustrative).
2. Release N writes it; readers tolerate null.
3. No backfill needed. No contract step (stays nullable by design).

## Worked example: rename `payload_json` to `variables_json`
Never rename in place. Expand: add `variables_json`; dual-write; backfill with a chunked job; switch reads; contract after one full release: drop `payload_json`.

## Risky operations
| Operation | Risk | Safer approach |
|---|---|---|
| `ADD COLUMN ... NOT NULL DEFAULT` on big table | rewrite/lock (older PG; Oracle fast with default metadata) | add nullable, backfill, add constraint |
| Add index on `message_request` | blocks writes (non-concurrent) | PG `CREATE INDEX CONCURRENTLY` (outside Flyway transaction; manual/`executeInTransaction=false` script, not portable: document per environment) |
| Change type | rewrite | add new column, copy, switch |
| Add unique constraint | fails on duplicates | detect duplicates first with a query in the migration pre-check |
| Drop column | breaks old pods | wait for N+2 |

## Testing
- Flyway runs on H2 for every integration test context (validates syntax portability).
- A migration test boots an empty DB, migrates to head, and asserts key constraints exist (see source `*MigrationTest`).
- Recommended for prod-like: migrate a snapshot copy with production-like volumes and time it (**not executed here**).

## Rollback
Schema rollback is "roll forward". Expand-only migrations are what make an app rollback safe: the previous app version ignores new columns. See `docs/operations/rollback-plan.md`.

## Review checklist
Cross-reference: `ai-engineering/standards/db-migrations.md` and `.github/instructions/sql-migrations.instructions.md`.
