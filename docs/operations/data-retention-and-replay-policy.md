# Data Retention and Replay Policy

## Retention
Numbers and mechanism: `docs/database/retention-and-archival.md`. Summary: operational rows 90 days, audit 400 days, idempotency/outbox/dedupe/callback guards days or hours, Mongo diagnostics 30 days TTL (all illustrative defaults).

## Replay policy
Replay = operator-initiated re-injection of a `FAILED` or `DEAD_LETTERED` message into the dispatch path (`POST /api/v1/dead-letters/{id}/replay`, `POST /api/v1/messages/{id}/requeue`). It is the only way a soft-terminal message leaves its state.

### Authorization
`OPERATOR` or `PLATFORM_ADMIN` only; tenant-scoped for operators; audited with actor, role, reason, correlation id.

### Preconditions (checked by `ReplayService`)
| Check | Behavior if fails |
|---|---|
| Status is `FAILED` or `DEAD_LETTERED` | 409 `ILLEGAL_STATE_TRANSITION` |
| Not past `expires_at` | rejected as expired (time-sensitive content must not go out late) |
| Policy re-evaluation (consent, suppression, quiet hours) | `REJECTED` with reason; replay never bypasses consent |
| Template pinned version still renderable | proceeds even if RETIRED (ADR-0007) |
| Last known outcome not ambiguous | if attempts include TIMEOUT/AMBIGUOUS/unknown, run reconciliation lookup first or require explicit confirmation flag (see source) to accept duplicate risk |
| Rate awareness | replay goes through the normal queue priority and provider token bucket |

### Rules of engagement
1. Find and fix root cause before replay (poison and bug cases).
2. Replay in batches; verify the first batch.
3. Replay creates a new `dispatchId` and attempt group; `attempt_count` continues (cap rules per source).
4. Prefer replay at LOW or original priority; never raise priority to jump the queue without reason.
5. Maximum replay age: default 7 days for operational messages (illustrative); older requires PLATFORM_ADMIN and a recorded business reason.
6. Discard: `POST /api/v1/dead-letters/{id}/discard` records reason; message stays `DEAD_LETTERED` with discarded flag; counts excluded from DLQ depth alerts after discard (see source).
7. Bulk replay (many ids) must be scripted with throttling (`scripts/` helper, see source); no unbounded loops.

### What is NOT replayable
`DELIVERED`, `REJECTED`, `CANCELLED` (hard terminals). To resend content intentionally, submit a new message with a new idempotency key.

### Replay of events (callbacks)
Provider callbacks are idempotent by `(provider_id, provider_event_id)`; re-posting a stored event is a no-op by design. There is no "replay from broker offset" capability (RabbitMQ; see ADR-0001 for the Kafka trade-off).

### Evidence
Audit actions for replay/discard/requeue; metrics `messaging.deadletter.total`, `messaging.transitions{from="DEAD_LETTERED",to="QUEUED"}`.
