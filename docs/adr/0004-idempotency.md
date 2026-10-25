# ADR-0004: Idempotency at four boundaries

Status: Accepted (2026-10-18)

## Context
Duplicates arise from client retries on timeout, broker redelivery (at-least-once), consumer crashes, duplicate/out-of-order provider callbacks, and operator double-clicks. A customer must not receive the same security alert twice because a load balancer retried.

## Decision
Four independent layers; none relies on another being perfect.

| Layer | Key | Mechanism | Outcome on duplicate |
|---|---|---|---|
| 1. API submission | `Idempotency-Key` header (required on `POST /messages`) + tenant | `IdempotencyService` claims `idempotency_record` (UNIQUE(tenant_id, idem_key)), status `IN_PROGRESS` then `COMPLETED` with stored response; Redis fast-path in front, DB is the source of truth | Same hash: HTTP 200 + `Idempotent-Replay: true` and original body. Different hash: 422 `IDEMPOTENCY_KEY_REUSED`. In flight: 409 `IDEMPOTENCY_IN_PROGRESS` |
| 2. Consumer | `dispatchId` (one per message, attemptNo) | `processed_message` UNIQUE(dispatch_id) insert + CAS `QUEUED to PROCESSING` | Loser acks and skips |
| 3. Callback | `(provider_id, provider_event_id)` | `delivery_event` UNIQUE; plus `replay_guard(event_id)` by `X-Event-Id` | Same `X-Event-Id` inside the replay window: **200** `{"result":"replayed"}`, no state change. Same `(provider_id, provider_event_id)` under another transport id: 202 `duplicate`, no second row (the unique constraint prevents one). Either way the provider stops retrying (`docs/architecture/callback-flow.md`) |
| 4. Provider call | `idempotencyRef = messageId:attemptGroup` | Passed to provider; compliant providers dedupe; also used by `lookup()` | Provider returns same result or `lookup` shows prior acceptance |

Details:
- Request hash = SHA-256 over canonical JSON of the semantic body (excluding `metadata` ordering noise); see `IdempotencyService` source.
- Bulk jobs derive deterministic keys `bulk-<jobId>-<externalRef>` so restart cannot double-submit.
- Idempotency records expire (`expires_at`, retention job); key reuse after expiry creates a new message by design (document to clients: keys must be unique per logical send for at least the TTL).
- The claim and the `message_request` insert are in one transaction, so a crash cannot leave a completed record without a message.

## Alternatives considered
| Option | Why not |
|---|---|
| Redis-only idempotency | Redis loss would permit duplicates; Redis is a fast path only (`ResilientCoordination` falls back to DB) |
| Server-generated dedupe on payload content | Legitimate repeats (two identical OTPs) would be dropped |
| Exactly-once broker semantics | Not possible across the provider boundary (see delivery-semantics doc) |
| Dedupe only at consumer | Does not stop duplicate `message_request` rows and duplicate audit trails |

## Consequences
- Positive: safe client retries, safe broker redelivery, safe webhook retries, restartable batch.
- Negative: extra write per submission; clients must generate keys.
- Residual risk: a provider that ignores `idempotencyRef` can still duplicate after an AMBIGUOUS outcome; hence ADR-0010 (no blind resend for EMAIL/SMS).

## Validation
- `FailureRecoveryIT#scenario02_duplicateSubmission_sameKey_returnsOriginal_noSecondMessage`, `#scenario03_duplicateAndOutOfOrderCallbacks_applyMonotonically`.
- Concurrency test: N parallel identical submissions yield exactly one `message_request` (see source `service` tests).
- Metric: `messaging.idempotency.hits`; callback duplicates counted in `messaging.callbacks{result="duplicate"}`.
