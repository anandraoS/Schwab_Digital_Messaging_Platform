# 12. Delivery Callbacks and Status Reconciliation

## 12.1 Why callbacks are hard

When a provider says "accepted", the story is not over. The real outcome (delivered, bounced, deferred, opened) arrives later as a **webhook** that the provider sends to a public URL. Webhooks are the platform's most exposed endpoint and the least reliable input: they can be forged, replayed, duplicated, delayed, reordered, or malformed. Meanwhile some sends end with no answer at all. The platform answers with two cooperating mechanisms: a hardened callback pipeline, and reconciliation for outcomes that never produce a callback.

## 12.2 The callback pipeline as implemented

`POST /api/v1/callbacks/{providerId}` is `permitAll` at the Spring Security layer and authenticated by HMAC inside `CallbackService`. The network is never trusted. The controller reads the body as **raw bytes** so the signature is computed over exactly what the provider sent.

{{diagram:callback}}

### Authentication

| Element | Rule |
|---|---|
| Headers | `X-Signature: t=<epoch>,v1=<hex>`, `X-Provider-Key-Id`, `X-Event-Id` |
| Signed content | HMAC-SHA256 over `t + "." + rawBody`; raw bytes, never re-serialised JSON |
| Comparison | Constant time (`MessageDigest.isEqual`, bitwise OR accumulation without early exit) |
| Freshness | `|now - t|` at most `messaging.callback.tolerance` (5 minutes) |
| Key choice | `X-Provider-Key-Id` must equal the provider's `provider_config.hmac_key_ref` (or `<ref>-next` during rotation); the secret comes from `messaging.callback.secrets.<keyId>` |
| Rotation | Several `v1=` values are accepted, so two secrets can be active during a rotation |
| Uniform failure | A bad signature, a stale timestamp, an unknown provider and an unbound key all return the same `401 INVALID_SIGNATURE`, so providers cannot be enumerated |

```java
// com.example.messaging.callback.SignatureVerifier (excerpt)
// bitwise OR keeps the loop free of early exits; isEqual is constant-time for equal lengths
match |= MessageDigest.isEqual(expected, c.getBytes(StandardCharsets.US_ASCII));
...
Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
```

### Replay and duplicates (implemented behaviour, DEVIATIONS item 7)

| Situation | HTTP | `result` | State change |
|---|---|---|---|
| Same `X-Event-Id` (key `providerId:eventId`) already processed within the 24 h replay window | **200** | `replayed` | none |
| New transport id but same `(provider_id, provider_event_id)` already stored | 202 | `duplicate` | none, the first row stays |
| Event applied or informational | 202 | `accepted` | status moved or event recorded |
| Rank-1 event after the outcome is known, or terminal receipt not applicable in the current state | 202 | `out_of_order` | recorded `applied=false` |
| Same terminal receipt again under a new event id | 202 | `stale` | recorded `applied=false`, `STALE` |
| Opposite terminal receipt after the first | 202 | `conflict` | first terminal kept, audit `DELIVERY_EVENT_CONFLICT` |
| Event for an unknown message | 202 | `unknown_message` | neither stored nor remembered (`delivery_event.message_id` is a mandatory foreign key) |
| Malformed payload or unknown vendor status | 400 | `malformed` | none |
| Signature problem | 401 | `invalid_signature` | none |

Why 200 for a replay rather than 409 `REPLAYED_EVENT`? The provider must **stop retrying** an event we already processed; the error code `REPLAYED_EVENT` exists in the enum but is not used. The replay guard records an event **only after successful processing**, backed by the `replay_guard` table with an in-memory cache.

A response example for a replay:

```json
{"result":"replayed","eventId":"evt-1001"}
```

### Normalisation

`EventNormalizer` finds the `ProviderPayloadMapper` bean for the `{providerId}` path segment and produces a canonical `NormalizedEvent` (`providerId`, `providerEventId`, `type`, `providerMessageId`, `idempotencyRef`, `occurredAt`, `receivedAt`, `reasonCode`, `rawRef`). Normalisation is pure and side-effect free. The raw payload is archived best-effort to Mongo, redacted. Free-text vendor reasons are never copied into audit or log lines: only code-like reasons (`[A-Z][A-Z0-9_]{0,39}`) are kept.

### Applying the event

`DeliveryEventProcessor` inserts the `delivery_event` row and applies `MessageStateMachine.resolveReceipt(currentStatus, type)` as a **compare-and-set** through `NotificationService.transition(..., viaReceipt=true)`, retried up to three times if the message moved concurrently, in the same transaction as the insert.

{{diagram:receiptRank}}

## 12.3 Ambiguous outcomes and reconciliation

Some sends never produce a clear answer: the provider call times out, the response is lost, the worker crashes between the provider's acceptance and the platform's database write, or the receipt callback never comes. The platform's rule (ADR-0010): **unknown is a state**, and it is resolved from provider evidence only.

{{diagram:reconcile}}

### Three detectors

| Detector | Condition | Result | Class |
|---|---|---|---|
| Consumer | Provider result `TIMEOUT` or `AMBIGUOUS` (EMAIL, SMS) | `PROCESSING -> DELIVERY_UNKNOWN` | `DispatchConsumer` |
| Sweeper, crash | `PROCESSING` with `locked_until` older than now (lock-ttl 2 min) | `-> DELIVERY_UNKNOWN`, audit `STUCK_SWEEP` | `StuckMessageSweeper` |
| Sweeper, silence | `SENT_TO_PROVIDER` with no receipt after `receipt-timeout` (15 min) | `-> DELIVERY_UNKNOWN` | `StuckMessageSweeper` |

### Reconciliation

`ReconciliationService` runs every 15 seconds (`reconcile-interval-ms`), selecting `DELIVERY_UNKNOWN` messages older than `reconcile-after` (5 minutes), in batches of 50, and calls `ChannelProvider.lookup(idempotencyRef)`.

| Lookup says | Action |
|---|---|
| Delivered | `DELIVERY_UNKNOWN -> DELIVERED`, with an evidence event `recon:<messageId>:<n>` |
| Failed or bounced | `-> FAILED` |
| Accepted, not yet delivered | Stay unknown; re-check later |
| Not received | `-> FAILED -> QUEUED` as a new attempt with the **same idempotency reference group**, so a compliant provider still deduplicates |
| Lookup error or provider without lookup | Stay unknown; increment the check count |
| After `reconcile-max-checks` (5) | Message remains in `GET /failed-messages` for an operator decision |

Spacing and the maximum are derived from `RECONCILIATION_CHECK` audit events: no extra column is needed (DEVIATIONS item 9).

### Invariants

1. A message in `DELIVERY_UNKNOWN` is never silently retried.
2. Reconciliation never creates `DELIVERED` without provider-originated evidence (a callback or a lookup result).
3. Late callbacks win over reconciliation (same rank rule; first terminal wins).
4. Every reconciliation action is audited with the lookup result.

### Decision matrix by channel

| Channel | After TIMEOUT or AMBIGUOUS | Why |
|---|---|---|
| EMAIL | Wait for callback, then `lookup`; retry only if "not received" | Duplicate emails are visible and reputational |
| SMS | Same | Duplicate cost and annoyance |
| PUSH | Retry allowed | The collapse key makes it idempotent on the device |

## 12.4 Tests that prove it

| Test | Proves |
|---|---|
| `SignatureVerifierTest` | Valid, tampered body, wrong key, stale timestamp, multiple `v1=` values |
| `ReplayGuardTest` | Replay window and keying |
| `EventNormalizerTest` | Mapping tables, unknown status, missing ids |
| `CallbackApiIT` | HTTP results (200 replayed, 202 variants, 400, 401) end to end |
| `ReconciliationIT` | Lookup outcomes, spacing, max checks, safe retry |
| `StuckMessageSweeperTest` | Lock expiry, receipt timeout |
| `FailureRecoveryIT#scenario03_*`, `scenario04_*`, `scenario05_*` | Duplicate, out-of-order and ambiguous cases, crash after provider accept |
| `tests/failure-recovery/callback-replay.mjs`, `provider-timeout.mjs` | Scripted replays against a started `local` application |

## Reference material

{{embed:docs/architecture/callbacks-normalization.md|title=Callback normalisation (current, implemented)|skip=Vendor payloads and mapping}}

{{embed:docs/architecture/reconciliation-flow.md|title=Reconciliation flow (design document)|only=Decision matrix;Invariants}}

{{embed:docs/adr/0010-ambiguous-outcome-and-reconciliation.md|title=ADR-0010: Ambiguous outcomes and reconciliation}}
