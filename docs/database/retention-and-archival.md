# Retention and Archival

Config: `messaging.retention.{message-days,audit-days,event-days}`; executor: `scheduler.RetentionJob`. Values below are illustrative defaults for a reference system, not regulatory advice (actual retention is a compliance decision; see `docs/security/data-minimization-and-retention.md`).

| Data | Default | Rationale | Mechanism |
|---|---|---|---|
| `message_request` (+ `message_attempt`) terminal rows | 90 days | support window, dispute handling | batched delete by `created_at` and terminal status, chunk 1000, sleep between chunks |
| `delivery_event` | 90 days (`event-days`) | follows message | batched delete |
| `audit_event` | 400 days (`audit-days`) | longer than data it describes | batched delete or archive-then-delete |
| `idempotency_record` | `expires_at` (e.g. 24-72 h) | clients retry within hours | delete expired |
| `processed_message` | 7 days | must exceed max broker redelivery/replay horizon | delete |
| `outbox_event` SENT | 7 days | debug | delete |
| `replay_guard` | 24 h (>= 2x tolerance) | callback replay window | delete |
| Mongo `provider_diagnostics` | 30 days | TTL index | automatic |
| `suppression` | indefinite | reputation/regulatory | manual review |
| `template_version` | indefinite | proof of what was sent | none |

## Rules
1. Never delete non-terminal messages (`RETRY_PENDING`, `DELIVERY_UNKNOWN`, etc.) regardless of age: they are alerted on instead.
2. Delete children before parents or use cascading only where safe; order: attempts/events, then message, then audit last.
3. Idempotency retention must exceed the outbox/replay horizon, otherwise a replayed old submission could create a new message.
4. `processed_message` retention must exceed maximum possible redelivery delay, including DLQ replay window. Replaying a message creates a new `dispatchId`, so replay is not blocked by dedupe.
5. Deletes are chunked (avoid long locks/bloat): `DELETE ... WHERE id IN (SELECT id ... FETCH FIRST n ROWS ONLY)`, committing per chunk; each run logs counts and emits audit `RETENTION_PURGE` (counts only).

## Archival (optional tier)
Before deletion, export aggregates (counts by day/tenant/channel/status/outcome) to a reporting table or object storage as Parquet/CSV; no PII. Full-row archival requires the same controls as production data (encryption, access governance) and is out of scope for the reference.

## Partitioning path
When volume makes batched deletes costly, partition `message_request`, `message_attempt`, `delivery_event`, `audit_event` by month and drop partitions. PostgreSQL requires the partition key in unique constraints; the idempotency uniqueness already lives in `idempotency_record` (small, separately purged) which is why message-level uniqueness is not the only line of defense. Oracle interval partitioning: see `oracle-mapping.md`.

## Right-to-erasure (design note)
Recipient `deleted` soft flag blocks sending (policy step 1). Hard erasure: null address columns and remove `recipient_preference`, keep `address_hash` suppression rows and anonymized audit records. Not implemented as an automated workflow here; listed in `prod-vs-reference.md`.
