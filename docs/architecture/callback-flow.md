# Callback (Delivery Receipt) Flow

`POST /api/v1/callbacks/{providerId}` is `permitAll` at the Spring Security layer and authenticated by HMAC inside `callback.CallbackService` (signature maths in `callback.SignatureVerifier`). Never trust the network. This document describes the **implemented** behaviour (`CallbackController`, `CallbackService`, `ReplayGuard`, `EventNormalizer`, `DeliveryEventProcessor`); `callbacks-normalization.md` has the vendor payload tables and `docs/design/DEVIATIONS.md` (stage 2, item 7) records why it differs from the first plan.

```mermaid
sequenceDiagram
  autonumber
  participant P as Provider
  participant CC as CallbackController
  participant CS as CallbackService
  participant SV as SignatureVerifier
  participant RG as ReplayGuard
  participant EN as EventNormalizer + mapper
  participant DP as DeliveryEventProcessor
  participant DB as Database
  participant DG as ProviderDiagnosticsStore
  P->>CC: POST raw bytes, X-Signature t=..,v1=.., X-Provider-Key-Id, X-Event-Id
  CC->>CS: handle(providerId, keyId, signature, eventId, rawBody)
  CS->>CS: provider known? key id == provider_config.hmac_key_ref (or ref + "-next")?
  CS->>SV: verify HMAC-SHA256(t + "." + rawBody), tolerance 5 min
  alt unknown provider, unbound key, no secret, bad or stale signature
    CS-->>P: 401 INVALID_SIGNATURE (same answer for every cause; callbacks{result=invalid_signature})
  end
  alt X-Event-Id missing or not 1-150 safe characters
    CS-->>P: 400 (callbacks{result=malformed})
  end
  CS->>RG: isReplay(providerId:eventId)?
  alt already processed within messaging.pipeline.replay-window
    CS-->>P: 200 {"result":"replayed"} (callbacks{result=replayed}), no state change
  end
  CS->>EN: normalize(providerId, rawBody, eventId, now)
  alt unknown vendor status, missing reference, bad timestamp ...
    CS->>DG: archive redacted raw payload (best effort)
    CS-->>P: 400 MALFORMED_REQUEST (callbacks{result=malformed})
  end
  CS->>DP: apply(event, rawRef) in ONE transaction
  DP->>DB: delivery_event with this (provider_id, provider_event_id)?
  alt already stored
    DP-->>CS: duplicate (no second row)
  else message not found (by provider message id, then idempotencyRef)
    DP-->>CS: unknown_message (nothing stored)
  else
    DP->>DP: MessageStateMachine.resolveReceipt(current, type) - monotonic rank rule
    DP->>DB: CAS status transition (up to 3 tries) + insert delivery_event + audit
    DP-->>CS: accepted / out_of_order / stale / conflict
  end
  CS->>RG: markSeen (not for unknown_message)
  CS->>DG: archive raw payload, redacted (best effort)
  CS-->>P: 202 {"result": ..., "eventId": ...}
```

## Responses
| Situation | HTTP | `result` / code | Metric `messaging.callbacks{result}` |
|---|---|---|---|
| Any signature problem, unknown provider, key not bound to the provider | 401 | `INVALID_SIGNATURE` | `invalid_signature` |
| Missing or unsafe `X-Event-Id`; authentic but unintelligible payload | 400 | `VALIDATION_FAILED` / `MALFORMED_REQUEST` | `malformed` |
| Replayed `X-Event-Id` (inside the replay window) | **200** | `replayed` | `replayed` |
| Applied or informational event | 202 | `accepted` | `accepted` |
| Same `(provider_id, provider_event_id)` already stored | 202 | `duplicate` | `duplicate` |
| Lower-rank or non-receivable receipt | 202 | `out_of_order` | `out_of_order` |
| Same terminal receipt again under a new event id | 202 | `stale` | `stale` |
| Opposite terminal receipt (DELIVERED vs BOUNCED/FAILED) | 202 | `conflict` | `conflict` |
| Message unknown (maybe the callback overtook our commit) | 202 | `unknown_message` | `unknown_message` |

`409 REPLAYED_EVENT` exists in the error-code list but is **not used**: a replay is answered 200 so the provider stops retrying an event we already processed.

## Rules
- Raw body bytes are used for HMAC (never re-serialised JSON); constant-time comparison (`MessageDigest.isEqual`); several `v1=` values are accepted so two secrets can be active during rotation (`docs/security/secrets-management.md`).
- The key id must be the provider's `provider_config.hmac_key_ref` (or `<ref>-next`); the secret comes from `messaging.callback.secrets.<keyId>` (dummy values locally, environment variables in `prod-like`). The seeded Acme key id is `acme-sms-key`.
- Replay guard key is `providerId:eventId`; it is recorded only after successful processing (`replay_guard` table plus in-memory cache) and is not recorded for `unknown_message` so the provider's retry can still succeed. Purging old rows is the retention job's task (`messaging.pipeline.replay-window`).
- Dedupe is the unique `(provider_id, provider_event_id)` on `delivery_event`. A duplicate therefore creates **no** second row (a row marked `ignored_reason=DUPLICATE` is not possible with that constraint). An unknown message is neither stored (`delivery_event.message_id` is a mandatory foreign key) nor remembered by the replay guard.
- Rows that are stored but not applied carry `ignored_reason` = `OUT_OF_ORDER`, `STATE_NOT_RECEIVABLE`, `STATE_CHANGED`, `STALE` or `CONFLICT`.
- A conflict keeps the first terminal outcome and writes the audit action `DELIVERY_EVENT_CONFLICT`.
- Free-text vendor reasons are never copied into audit or log lines (only code-like reasons); raw payloads go to Mongo `provider_diagnostics`, redacted, best effort (`messaging.diagnostics.write.failures` counts failures; they never fail the callback).
- Side effects of a FAILED receipt: hard bounce adds a suppression for the address; `TOKEN_INVALID` suppresses the push token; an `UNSUBSCRIBED` event clears the marketing opt-in (audited).
- Payload limit 256 KB, enforced before parsing (`RequestSizeFilter`).

## Ranking
| Event | Rank | Effect on state |
|---|---|---|
| PROVIDER_ACCEPTED, SENT, DEFERRED | 1 | never downgrades; a receipt that overtakes the consumer while the message is `PROCESSING` moves it to `SENT_TO_PROVIDER` |
| OPENED, CLICKED, UNSUBSCRIBED | 2 (engagement) | recorded as applied; never moves the status; UNSUBSCRIBED updates the preference |
| DELIVERED | 3 | to `DELIVERED` from SENT_TO_PROVIDER, PROCESSING, DELIVERY_UNKNOWN |
| BOUNCED, FAILED, REJECTED | 3 | to `FAILED` (hard bounce also suppresses); conflict with an earlier DELIVERED keeps the first and audits `DELIVERY_EVENT_CONFLICT` |

Tests: `SignatureVerifierTest`, `ReplayGuardTest`, `EventNormalizerTest`, `CallbackApiIT`, `FailureRecoveryIT#scenario03_duplicateAndOutOfOrderCallbacks_applyMonotonically`, `NewProviderWithoutOrchestrationChangeTest`. See also `callbacks-normalization.md`, ADR-0004, ADR-0010 and `docs/security/threat-model.md` (forged and replayed callbacks).
