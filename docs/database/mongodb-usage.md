# MongoDB Usage (provider diagnostics only)

## Scope
MongoDB holds one collection, `provider_diagnostics`: sanitized provider request/response snippets per attempt and the archive of raw callback payloads. It never holds lifecycle state, idempotency data, consent, or content bodies. Package `diagnostics` (`ProviderDiagnosticsStore`, `DiagnosticsController`, `GET /api/v1/diagnostics/messages/{id}`, roles OPERATOR/PLATFORM_ADMIN). Local/test: in-memory `de.bwaldvogel:mongo-java-server` 1.47.0. Prod-like: real MongoDB replica set (**not executed here**).

## Why Mongo here and not PostgreSQL
| Reason | Detail |
|---|---|
| Variable shape | Each provider returns a different payload; schema-less storage avoids a migration per vendor field |
| Volume and value | High write volume, low per-row value, short life: keeps hot OLTP tables and WAL small |
| Independent failure domain | Diagnostics outage must not affect sending (ADR-0005) |
| Cheap TTL | TTL index expires documents without cron/DELETE |
Honest counter-argument: PostgreSQL JSONB could do this with one less datastore; we chose Mongo to keep the portable-SQL rule (no JSONB) and to demonstrate a polyglot boundary with explicit consistency trade-offs.

## Document shape (illustrative)
```json
{
  "_id": "uuid",
  "tenantId": "tenant-retail",
  "messageId": "…",
  "attemptNo": 1,
  "providerId": "sim-sms",
  "kind": "REQUEST|RESPONSE|CALLBACK",
  "providerEventId": "evt-1001",
  "capturedAt": {"$date": "2026-10-18T10:15:30Z"},
  "expireAt":   {"$date": "2026-11-17T10:15:30Z"},
  "payload": { "...": "redacted via common.Redaction" },
  "correlationId": "…"
}
```
Never stored: message body, full phone/email (masked or hashed via `Redaction`), credentials, signature secrets.

## Consistency model
- **Best-effort, after the fact**: written after the PostgreSQL transaction outcome is known (or asynchronously). A failed write increments `messaging.diagnostics.write.failures` and is logged at WARN; processing continues.
- No distributed transaction. Possible states: DB has the event but Mongo lacks the document (`delivery_event.raw_ref` dangling), or Mongo has a document for an attempt whose DB transaction rolled back (orphan). Both are tolerated: diagnostics are advisory.
- Reads handle missing documents (`404` or empty list in `/diagnostics/messages/{id}`), never an error.

## Reconciliation risks
| Risk | Mitigation |
|---|---|
| Dangling `raw_ref` | UI shows "diagnostics unavailable"; no functional dependency |
| Orphan documents | TTL expiry; periodic report comparing `messageId` set vs DB (optional job; see source) |
| Replay depending on Mongo payload | Forbidden: replay and reconciliation use PostgreSQL + provider `lookup` only |
| Sensitive data leakage | Redaction at write, field allow-list, TTL, access limited to OPERATOR/PLATFORM_ADMIN, tenant filter on read |
| Clock skew on TTL | TTL uses `expireAt` set by app `Clock` |

## Indexes
| Index | Purpose |
|---|---|
| `{messageId: 1, capturedAt: 1}` | per-message diagnostics view |
| `{tenantId: 1, capturedAt: -1}` | tenant-scoped browsing |
| `{providerId: 1, providerEventId: 1}` (unique, sparse) | dedupe callback archive (best-effort) |
| `{expireAt: 1}` with `expireAfterSeconds: 0` | TTL |
Retention: default 30 days (config; illustrative), shorter than `delivery_event` retention.

## Migration / evolution
- Schema additions need no migration (additive fields; readers tolerate absence). Use a `schemaVersion` field when payload structure changes.
- Moving to PostgreSQL JSONB or object storage later: dual-write behind `ProviderDiagnosticsStore` port, backfill is optional (data is expendable), cut over reads.
- Moving off Mongo entirely is safe because nothing depends on it for correctness.

## Operations
Write concern `w=1` is adequate (data is expendable), read preference primary. Alert on `messaging.diagnostics.write.failures` rate; runbooks treat this as informational unless sustained. Backups are optional for this data.
