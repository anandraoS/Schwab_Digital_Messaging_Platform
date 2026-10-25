# Acceptance Criteria

Given/When/Then, by area. IDs are referenced by tests and sprint tasks. Automated tests should carry the AC id in a display name or comment.

## Submission
- **AC-1** Given a valid token (SENDER, tenant A) and a unique `Idempotency-Key`, when `POST /api/v1/messages` is called with a valid body, then the response is 202 with `Location`, status `VALIDATED` or `QUEUED` is observable via `GET /messages/{id}/status`, and one `outbox_event` and one `audit_event` exist.
- **AC-2** Given an already-completed key with identical body, when repeated, then 200 + `Idempotent-Replay: true` and the same id; no new rows.
- **AC-3** Given the same key with a different body, then 422 `IDEMPOTENCY_KEY_REUSED`.
- **AC-4** Given 20 concurrent identical requests, then exactly one message exists.
- **AC-5** Given a body > 256 KB then 413 `PAYLOAD_TOO_LARGE`.
- **AC-6** Given missing required template variable then 400 `TEMPLATE_VARIABLE_MISSING` and nothing persisted.

## Security and tenancy
- **AC-10** Given tenant B token, when requesting tenant A's message by id, then 404 for GET, cancel, history, status.
- **AC-11** Given no token / expired token then 401; given SENDER calling operator endpoints then 403.
- **AC-12** Given an unsigned or wrongly signed callback then 401 and no state change; given a stale timestamp (> 5 min) then 401.
- **AC-13** Given a replayed callback (same `X-Event-Id`) then no second state change.
- **AC-14** `MessageDetail` never contains rendered body or unmasked address.

## Policy
- **AC-20** Given marketing to opted-out recipient then REJECTED `OPTED_OUT`; fallback channels also respect consent.
- **AC-21** Given hard-suppressed address then every category REJECTED `SUPPRESSED`.
- **AC-22** Given marketing inside quiet hours then deferred (VALIDATED with moved `scheduled_at`); SECURITY_ALERT is sent.

## Lifecycle and reliability
- **AC-30** Illegal transitions are impossible via API (409) and not reachable by events (logged+audited).
- **AC-31** Given provider TRANSIENT_FAILURE then RETRY_PENDING with `next_attempt_at` within [0.5, 1.0] x min(cap, base x 2^(n-1)) of now.
- **AC-32** Given attempts exhausted without fallback then DEAD_LETTERED(RETRIES_EXHAUSTED); operator replay re-evaluates policy.
- **AC-33** Given TIMEOUT then DELIVERY_UNKNOWN with exactly one provider call; no automatic resend for EMAIL/SMS until lookup says not received.
- **AC-34** Given DELIVERED callback before SENT callback then final status DELIVERED, SENT recorded `applied=false`.
- **AC-35** Given provider acceptance without callback then after `receipt-timeout` status DELIVERY_UNKNOWN (never DELIVERED).
- **AC-36** Given a poison envelope then DLQ POISON_MESSAGE within `poison-threshold` deliveries.
- **AC-37** Given a template retired after accept then the message still renders with the pinned version.

## Templates
- **AC-40** Approver equal to author is refused (403/409); different approver publishes; previous version retired.
- **AC-41** Editing a PUBLISHED version is refused.
- **AC-42** Preview never sends and escapes HTML.

## Bulk
- **AC-50** CSV with duplicates and invalid rows: job COMPLETED_WITH_ERRORS, failures listed, one message per valid unique row.
- **AC-51** Restart after interruption produces no duplicates.
- **AC-52** Missing `messages:bulk` scope then 403.

## Operations
- **AC-60** `/api/v1/ops/queues` shows depth per priority, DLQ depth, retry-pending, oldest age.
- **AC-61** Metrics in CONTRACT section 8 appear on `/actuator/prometheus`.
- **AC-62** Backpressure over `max-queue-depth` yields 503 + `Retry-After`.

## Non-functional (illustrative targets, measured only when executed)
- **AC-70** POST /messages p95 < 150 ms on local harness at agreed load (record only from executed runs).
- **AC-71** Graceful shutdown completes in-flight within 30 s and loses no message.
