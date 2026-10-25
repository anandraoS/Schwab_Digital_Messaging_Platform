# ADR-0009: Transactional outbox and dual-write avoidance

Status: Accepted (2026-10-18)

## Context
Accepting a message means (a) persisting `message_request` and (b) publishing to the broker. Doing both without a shared transaction ("dual write") yields two failure modes: DB commit then broker publish fails = **lost message** (client got 202, nothing sent); broker publish then DB commit fails = **ghost message** (dispatch for a message that does not exist).

## Decision
Use the **transactional outbox**:
1. In one DB transaction `NotificationService` writes `message_request` (VALIDATED), `outbox_event` (`status=NEW`), `idempotency_record` completion and an `audit_event`.
2. `scheduler.OutboxPublisher` runs every 200 ms, selects `status=NEW AND available_at <= now` (scheduled and quiet-hours-deferred messages wait in the outbox until due), claims each row with a `markSent` compare-and-set, moves the message `VALIDATED to QUEUED` (CAS, tolerating an already QUEUED message for requeue/replay events) and publishes the `DispatchEnvelope` to `MessageBus`, all inside **one transaction**: a failed publish rolls the claim back, giving at-least-once delivery (`docs/design/DEVIATIONS.md`, stage 2 item 3).
3. Publication is **at-least-once**: a crash between publish and marking `SENT` re-publishes; the consumer dedupes by `dispatchId` (ADR-0004), so duplicates are harmless.
4. Scheduled sends: `ScheduledDispatchPoller` enqueues messages whose `scheduled_at <= now` using the same outbox path.
5. Multi-instance safety uses DB row claim (CAS), not ShedLock; no leader election.
6. If the outbox lags (broker down), API backpressure (`messaging.backpressure.max-queue-depth`) returns 503 + `Retry-After` rather than growing unboundedly.

Ghost-message case cannot occur (publish happens after commit). Lost-message case cannot occur (row persisted with the request).

## Alternatives considered
| Option | Why not |
|---|---|
| Publish inside transaction (best-effort) | Dual write |
| Two-phase commit/XA across DB and Rabbit | Operationally fragile, limited support |
| Change Data Capture (Debezium) on outbox | Excellent at scale; adds infrastructure; documented as evolution path |
| Publish-then-persist | Ghost messages |
| Skip broker, poll `message_request` only | Higher latency, DB load; used only as the fallback/reconciler |

## Consequences
- Positive: no lost/ghost dispatch; replay is a DB operation.
- Negative: up to ~200 ms added latency (+ DB polling load); outbox table needs retention (`RetentionJob` purges `SENT` rows).
- Risk: poller stall = silent backlog. Alert on oldest `NEW` outbox age and `messaging.dispatch.delay` p95 (see slis-slos). Monitor index on `outbox_event(status, created_at)` (see source migration).

## Validation
- Tests: crash-between-publish-and-mark replays once with a single provider call (consumer dedupe); `FailureRecoveryIT#scenario06_databaseOutage_submissionFailsClosed_thenRecovers`.
- Sequence: `docs/architecture/submission-flow.md`.
