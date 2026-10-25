# 5. Notification Submission Flow

## 5.1 What the caller sees

`POST /api/v1/messages` is the front door. It is open to `SENDER`, `TENANT_ADMIN` and `PLATFORM_ADMIN` (`@PreAuthorize` on `MessageController.submit`) and it answers **202 Accepted** as soon as the message is safely persisted. The request thread never talks to a provider: delivery happens later, asynchronously, and the caller learns the outcome by polling `GET /api/v1/messages/{id}/status` or reading the lifecycle timeline.

| Aspect | Rule | Source |
|---|---|---|
| Required header | `Idempotency-Key` (1 to 128 characters) | `MessageController` |
| Body limit | 256 KB, else 413 `PAYLOAD_TOO_LARGE` | `RequestSizeFilter` |
| Rate limit | 50 req/s per client, burst 100, else 429 with `Retry-After` | `RateLimitFilter` |
| Tenant | Always taken from the JWT `tenant` claim, never from the body | `TenantContext` |
| Success | 202 with `Location: /api/v1/messages/{id}` | `MessageController` |
| Replay | 200 with `Idempotent-Replay: true` and the original body | `MessageController` |
| Backpressure | 503 `SERVICE_UNAVAILABLE` with `Retry-After` when queue depth plus due outbox rows reach `messaging.backpressure.max-queue-depth` (5000) | `BackpressureGuard` |
| Database down | 503 with `Retry-After: 5` (fail closed) | `GlobalExceptionHandler` |

## 5.2 The submission transaction

{{diagram:submission}}

The sequence has a deliberate shape. Everything that can **reject cheaply** happens first (size, rate limit, authentication, backpressure, idempotency claim). Everything that **changes state** happens in one transaction at the end. If anything in that transaction fails, the whole transaction rolls back, including the idempotency claim, so the client's retry is a clean retry.

```java
// MessageController.submit (excerpt)
backpressure.check(); // 503 + Retry-After when the dispatch backlog is full
NotificationService.SubmitResult result = notifications.submit(request, idempotencyKey);
if (result.replay()) {
    return ResponseEntity.ok().header("Idempotent-Replay", "true")
            .location(URI.create("/api/v1/messages/" + body.id())).body(body);
}
return ResponseEntity.status(HttpStatus.ACCEPTED)
        .location(URI.create("/api/v1/messages/" + body.id())).body(body);
```

### State written by one submission

| Row | Table | Content |
|---|---|---|
| Message | `message_request` | `ACCEPTED` then `VALIDATED` (or `REJECTED`), template pinned (code, version, locale), variables stored, rendered text **not** stored |
| Outbox | `outbox_event` | `NEW`, `available_at` = now, or the scheduled time |
| Audit | `audit_event` | `MESSAGE_ACCEPTED` (or `MESSAGE_REJECTED`) |
| Idempotency | `idempotency_record` | request hash and the full response, status `COMPLETED` |

## 5.3 Implemented behaviour: policy rejection returns 202

The build contract originally allowed `POLICY_SUPPRESSED` as a 4xx. The implementation decided otherwise (DEVIATIONS, "Backend foundation", item 2): policy rejections at submission (opt-out, suppression, deleted recipient, no address, expiry before a deferred send) are **persisted** as `REJECTED` messages and answered **202** with `status=REJECTED` and a `statusReason`. This keeps the idempotent replay and the audit trail consistent: the same request with the same key returns the same answer, and the rejection is visible in the history.

```java
// NotificationService (excerpt): policy is evaluated for the time the message would actually be sent
PolicyDecision decision = policy.evaluate(recipient, req.channel(), req.category(), req.priority(), sendAt);
MessageStatus status = MessageStatus.VALIDATED;
...
if (decision.isRejected()) { status = MessageStatus.REJECTED; reason = decision.reason().name(); }
else if (decision.isDeferred()) { scheduledAt = decision.until(); reason = "DEFERRED_QUIET_HOURS"; ... }
MessageStateMachine.assertTransition(MessageStatus.ACCEPTED, status);
```

The error code `POLICY_SUPPRESSED` therefore exists in the `ErrorCode` enum but is not returned by submission.

## 5.4 Idempotency: four layers

{{diagram:idempotencyLayers}}

| Layer | Key | Guarantee | Where |
|---|---|---|---|
| API | `(tenant_id, idempotency_key)` unique | One message per client intent; same key and same hash replays the original response | `IdempotencyService`, table `idempotency_record` |
| Consumer | `processed_message.dispatch_id` unique plus CAS status claim | One dispatch per (message, attempt) even when the broker redelivers | `DispatchConsumer` |
| Callback | `replay_guard` plus `UNIQUE(provider_id, provider_event_id)` | One effect per vendor event | `ReplayGuard`, `DeliveryEventProcessor` |
| Provider | `idempotencyRef = messageId:attemptGroup` | A compliant provider deduplicates a retried send | `ProviderRequest` |

The API layer has three outcomes for a key that was seen before:

| Situation | Response | Error code |
|---|---|---|
| Same key, same request hash, completed | 200 + `Idempotent-Replay: true`, original body | none |
| Same key, different request hash | 422 | `IDEMPOTENCY_KEY_REUSED` |
| Same key, the first request still in flight | 409 | `IDEMPOTENCY_IN_PROGRESS` |

An in-flight claim lives for `messaging.idempotency.claim-ttl` (default 30 s); a completed record for `record-ttl` (default 24 h). Redis is a fast path in front of the table; if Redis is down, `ResilientCoordination` falls back and the database unique key still enforces correctness (scenario 7 in Chapter 22).

## 5.5 Validation rules

| Field | Rule |
|---|---|
| `recipientId` xor `recipient.externalRef` | Exactly one; the recipient must exist in the caller's tenant (a foreign recipient is a 422 `RECIPIENT_NOT_FOUND`) |
| `channel` | `EMAIL`, `SMS` or `PUSH`; anything else is 400 `UNSUPPORTED_CHANNEL`; the recipient must have a valid address for it |
| `category`, `priority` | Enums; the category must match the template's category |
| `template.code`, `locale`, `version` | A `PUBLISHED` version is resolved and pinned; `version: null` means the latest published at accept time |
| `variables` | All required variables present, no unknown ones, string values with length limits (at most 50 properties) |
| `schedule.sendAt`, `expiresAt` | `sendAt` at most 366 days ahead (a past or missing value means now); `expiresAt` after `sendAt` |
| `fallbackChannels` | Each evaluated through the full policy chain |

## 5.6 Scheduled and deferred messages

`SCHEDULED` is **not** a status. A message with a future `sendAt`, or one deferred by quiet hours, is `VALIDATED` with `scheduled_at` set, and its outbox row carries `available_at` equal to the due time. The publisher selects `status=NEW AND available_at <= now`, so the row simply waits in the outbox. A message whose `expiresAt` precedes its deferred send time is `REJECTED` with `EXPIRES_BEFORE_DEFERRED_SEND`.

## 5.7 Edge cases

| Case | Behaviour |
|---|---|
| Duplicate request | Original response with `Idempotent-Replay: true` |
| 20 concurrent identical requests | Exactly one message (AC-4, scenario 2) |
| Template retired after accept | Still renders: the version is pinned (ADR-0007) |
| Provider timeout later | `DELIVERY_UNKNOWN`, then reconciliation |
| Backpressure | 503 and `Retry-After`; the idempotency claim is rolled back, so the retry with the same key is fresh |
| Marketing to an opted-out recipient | 202 with `status=REJECTED`, `statusReason=OPTED_OUT` |

## 5.8 Tests that prove it

| Test | Proves |
|---|---|
| `NotificationApiIT` | Submit, status, list, cancel and the error matrix over HTTP |
| `IdempotencyServiceTest` | Claim, replay, conflict and in-progress outcomes |
| `PolicyServiceTest` | The ordered policy chain, table driven |
| `MessageLifecycleIT` | Transitions end to end through the service layer |
| `FailureRecoveryIT#scenario02_*` | Sequential and concurrent duplicates yield one row |
| `BackpressureIT` | 503 with `Retry-After`, nothing persisted for the refused request |
| `OpenApiContractIT` | The served API matches the committed OpenAPI spec |

## Reference material

{{embed:docs/architecture/submission-flow.md|title=Submission flow (design document)|note=Where this document says policy suppression is either POLICY_SUPPRESSED or accepted-then-REJECTED, the implemented behaviour is accepted-then-REJECTED with HTTP 202.}}

{{embed:docs/adr/0004-idempotency.md|title=ADR-0004: Idempotency}}
