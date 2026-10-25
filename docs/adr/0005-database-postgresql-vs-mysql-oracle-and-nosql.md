# ADR-0005: Database choice (PostgreSQL vs MySQL vs Oracle; relational vs NoSQL)

Status: Accepted (2026-10-18)

## Context
Core data is relational and transactional: message state, attempts, idempotency, outbox, audit, templates, preferences. We need strong consistency for state transitions, unique constraints for dedupe, and ad-hoc operational queries. Many enterprises run Oracle; the SQL must stay portable. Provider diagnostics and raw callback payloads are variable-shape, write-heavy, low-value-per-row and expire quickly.

## Decision
1. **PostgreSQL 16** is the primary store. Local/test use **H2 2.x in `MODE=PostgreSQL`**.
2. **Portable SQL rule**: Flyway migrations must run on H2 and PostgreSQL: no partial indexes, no JSONB, no `ON CONFLICT`, only `TIMESTAMP`, `VARCHAR`, `BIGINT`, `CLOB`-like `VARCHAR(4000)`. This keeps an **Oracle** port mechanical: see `docs/database/oracle-mapping.md` and the reference DDL in `backend/src/main/resources/db/oracle/`.
3. **MongoDB** is used only for the `provider_diagnostics` collection (raw provider request/response snippets and callback payload archive). It is best-effort: a failed write increments `messaging.diagnostics.write.failures` and never fails message processing. `delivery_event.raw_ref` points at the Mongo document. See `docs/database/mongodb-usage.md`.
4. State and unique-constraint dedupe never live in Mongo or Redis.

## Alternatives considered
| Option | Pros | Cons | Verdict |
|---|---|---|---|
| MySQL 8 | Ubiquitous | Weaker transactional DDL, historically looser constraint behavior, less aligned with Oracle-style analytics SQL | Not chosen; schema would also port |
| Oracle | Common in enterprises; partitioning, advanced features | Licensing, no local parity without containers, proprietary hints/MERGE | Supported via mapping doc, not default |
| MongoDB for everything | Flexible schema | No multi-document uniqueness+state guarantees as simple as SQL; harder joins for ops views | Rejected for core data |
| Cassandra/Dynamo | Scale | Weak ad-hoc query, no cross-row transactions | Overkill |
| PostgreSQL JSONB for diagnostics | One datastore | Violates portable-SQL rule; bloats hot tables | Rejected; Mongo is isolated |

## Consequences
- Positive: ACID transitions with CAS; H2 gives fast tests; portable DDL.
- Negative: no JSONB convenience (JSON stored as `VARCHAR(4000)` text such as `payload_json`, `detail_json`); upsert is written as update-then-insert or guarded insert; two datastores to operate.
- Risks: H2 vs PostgreSQL divergence (locking, planner): mitigate with `*ContainerIT` PostgreSQL tests in `-Pcontainers` (not executed here) and explicit review of every migration (`ai-engineering/standards/db-migrations.md`).
- Mongo consistency risk: diagnostics can be missing for a message; reconciliation never depends on them.

## Validation
- Flyway runs on H2 in every `AbstractIntegrationTest` context. PostgreSQL execution: **NOT executed** here.
- Link: `docs/database/data-model.md`, `docs/database/query-plans-and-indexes.md`.
