# Callback Normalization: provider payload to `DeliveryEvent`

Providers send heterogeneous webhooks. `callback.EventNormalizer` dispatches to a per-provider mapper (by `{providerId}` path segment) and always produces the same internal model, so `DeliveryEventProcessor` is provider-agnostic.

## Canonical model `DeliveryEvent`
| Field | Type | Source/rule |
|---|---|---|
| `providerId` | string | path variable, must match a `provider_config` row |
| `providerEventId` | string | provider's event id; falls back to `X-Event-Id` header; must be non-blank (else 400 `MALFORMED_REQUEST`, recorded for diagnostics) |
| `type` | `DeliveryEventType` | mapped from the vendor status (tables below); an unknown vendor status is not guessed: the payload is rejected as malformed (400) |
| `providerMessageId` | string | vendor message id returned at send time |
| `idempotencyRef` | string, optional | echoed `messageId:attemptGroup` when vendor supports metadata echo |
| `occurredAt` | instant | vendor timestamp converted to UTC; missing = `receivedAt` |
| `receivedAt` | instant | server `Clock` |
| `reasonCode` | string, optional | vendor error code, stored for ops, never shown raw to end customers |
| `rawRef` | string | key of the archived raw payload in Mongo `provider_diagnostics` (best-effort) |

Message lookup order: see "Processing after normalization" below (an unknown message is acknowledged as `unknown_message`, it is not stored).

## Vendor payloads and mapping (as implemented)
The four mappers live in package `callback` (`SimulatedEmailPayloadMapper`, `SimulatedSmsPayloadMapper`, `SimulatedPushPayloadMapper`, `AcmeSmsPayloadMapper`), each a `ProviderPayloadMapper` bean that `EventNormalizer` finds by provider id. Every mapper can also **render** a vendor payload from a canonical event, which is how the provider simulators and the tests produce callbacks that take the production path. Vendor statuses are matched case-insensitively; an unknown status, a missing status, a missing message reference (neither provider message id nor idempotency ref), a missing event id (payload and `X-Event-Id` header both absent), an unparsable timestamp or an id with characters outside `[A-Za-z0-9._:~=+/-]` makes the payload **malformed**: the endpoint answers `400 MALFORMED_REQUEST` (nothing is echoed back), counts `messaging.callbacks{result="malformed"}` and archives the raw payload (redacted) for diagnostics.

| Provider (path id) | Payload shape | Message reference | Event id | Timestamp |
|---|---|---|---|---|
| `simulated-email` | `{"event","message_id","event_id","timestamp","meta":{"idempotencyRef"},"reason"}` | `message_id` and/or `meta.idempotencyRef` | `event_id` (else `X-Event-Id`) | ISO-8601 `timestamp` |
| `simulated-sms` | `{"status","msg_id","event_id","ts","ref","err"}` | `msg_id` and/or `ref` | `event_id` (else header) | ISO-8601 `ts` |
| `simulated-push` | `{"type","notification_id","id","time","ref","error"}` | `notification_id` and/or `ref` | `id` (else header) | ISO-8601 `time` |
| `acme-sms` | `{"ref","state","ts","id","code"}` | `ref` is Acme's own message id (no idempotency echo) | `id` (else header) | `ts` in epoch **seconds** (number) |

| Provider | Vendor status | `DeliveryEventType` |
|---|---|---|
| simulated-email | `queued` / `sent` | PROVIDER_ACCEPTED / SENT |
| simulated-email | `delivered` | DELIVERED |
| simulated-email | `hard_bounce` | BOUNCED (adds suppression of the address) |
| simulated-email | `soft_bounce` | DEFERRED |
| simulated-email | `failed` / `rejected` | FAILED / REJECTED |
| simulated-email | `open` / `click` / `unsubscribe` | OPENED / CLICKED / UNSUBSCRIBED (unsubscribe clears marketing opt-in) |
| simulated-sms | `ACCEPTD` / `SENT` / `DEFERRD` | PROVIDER_ACCEPTED / SENT / DEFERRED |
| simulated-sms | `DELIVRD` | DELIVERED |
| simulated-sms | `UNDELIV` / `REJECTD` | FAILED / REJECTED |
| simulated-push | `accepted` / `sent` / `deferred` | PROVIDER_ACCEPTED / SENT / DEFERRED |
| simulated-push | `delivered` | DELIVERED |
| simulated-push | `token_invalid` | FAILED with reason `TOKEN_INVALID` (suppresses the recipient's push token) |
| simulated-push | `failed` | REJECTED |
| simulated-push | `opened` / `clicked` | OPENED / CLICKED |
| acme-sms | `queued` / `sent` | PROVIDER_ACCEPTED / SENT |
| acme-sms | `ok` / `fail` | DELIVERED / FAILED |

Example email payload:
```json
{"event":"delivered","message_id":"em-77ab","event_id":"evt-1001","timestamp":"2026-10-18T10:15:30Z","meta":{"idempotencyRef":"3f2c...:1"}}
```
maps to `NormalizedEvent{providerId=simulated-email, providerEventId=evt-1001, type=DELIVERED, providerMessageId=em-77ab, idempotencyRef=3f2c...:1, occurredAt=2026-10-18T10:15:30Z}`.

## Processing after normalization (`DeliveryEventProcessor`)
Message lookup order: see "Processing after normalization" below (an unknown message is acknowledged as `unknown_message`, it is not stored).

| Result tag (`messaging.callbacks{result}`) | HTTP | Meaning | `delivery_event` row |
|---|---|---|---|
| `accepted` | 202 | applied (status moved) or informational/engagement event | `applied=true` |
| `duplicate` | 202 | `(provider_id, provider_event_id)` already stored (e.g. same payload event id under a different transport id, or after the replay window) | none (the first row stays) |
| `replayed` | 200 | the same `X-Event-Id` was already processed within `messaging.pipeline.replay-window` (24 h); no state change | none |
| `out_of_order` | 202 | a rank-1 event (SENT, ACCEPTED) after the outcome is known, or a terminal receipt that cannot apply in the current state | `applied=false`, `ignored_reason=OUT_OF_ORDER` / `STATE_NOT_RECEIVABLE` |
| `stale` | 202 | the same terminal receipt again under a new event id | `applied=false`, `ignored_reason=STALE` |
| `conflict` | 202 | opposite terminal receipt after the first one (DELIVERED then BOUNCED/FAILED, or the reverse): first terminal wins, audit `DELIVERY_EVENT_CONFLICT` | `applied=false`, `ignored_reason=CONFLICT` |
| `unknown_message` | 202 | see above | none |
| `malformed`, `invalid_signature` | 400, 401 | see `callback-flow.md` | none |

The decision is `MessageStateMachine.resolveReceipt(currentStatus, type)` (monotonic rank rule, CONTRACT 3.4); the transition is a compare-and-set through `NotificationService.transition(..., viaReceipt=true)`, retried up to three times if the message moved concurrently, in the same transaction as the `delivery_event` insert. Free-text vendor reasons are never copied into the audit trail: only code-like reasons (`[A-Z][A-Z0-9_]{0,39}`) are kept.

Replay choice: a replayed `X-Event-Id` is answered **200 `{"result":"replayed"}`** (not 409 `REPLAYED_EVENT`) because the provider must stop retrying an event we have already processed. The guard key is `providerId:eventId` (so two vendors may reuse an id), recorded only after successful processing, backed by the `replay_guard` table with an in-memory cache.

Authentication specifics: the key id must be the provider's `provider_config.hmac_key_ref` (or `<ref>-next` during rotation), the secret comes from `messaging.callback.secrets.<keyId>`, `t` must be within `messaging.callback.tolerance` (5 min), several `v1=` values are accepted so two secrets can be active. A signature failure, an unknown provider and an unbound key all give the same `401 INVALID_SIGNATURE` so providers cannot be enumerated.

## Adding a mapper
Implement `ProviderPayloadMapper` and register it as a Spring bean (that is all: `EventNormalizer` collects the beans by provider id), add the vendor-status table rows to this file, add `messaging.callback.secrets.<keyId>` config (dummy value locally) matching `provider_config.hmac_key_ref`, add mapper unit tests with duplicate, out-of-order and unknown-status cases (see `EventNormalizerTest`). `NewProviderWithoutOrchestrationChangeTest` proves this with a vendor that exists only in the test. See `specs/0002-new-provider-adapter.md`.

## Processing rules after normalization
See `callback-flow.md`: dedupe on `(provider_id, provider_event_id)`, rank rule, conflict audit. Normalization must be pure and side-effect free (testable without DB). Payload limits: body <= 256 KB (`RequestSizeFilter`); mapper must not log raw payload at INFO (may contain addresses).
