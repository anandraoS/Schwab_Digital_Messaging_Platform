# 9. Email, SMS, and Push Workflows

## 9.1 One pipeline, three personalities

All three channels travel the same pipeline: submit, outbox, bus, consumer, provider, callback. What differs is the content rules enforced at authoring and render time, what an ambiguous result means, and what a receipt means.

{{diagram:channels}}

| Aspect | EMAIL | SMS | PUSH |
|---|---|---|---|
| Address used | `recipient.email` | `recipient.phone_e164` | `recipient.push_token` |
| Subject | Required, at most 255 characters; CR and LF stripped (header injection defence) | Must be empty | Required (the notification title), at most 65 characters |
| Body | HTML or text, at most 10000 characters authored, 200000 rendered; **variable values are HTML-escaped** | Plain text; encoding GSM-7 (160 or 153 characters per segment, extension characters cost 2) or UCS-2 (70 or 67); at most 10 segments | At most 240 characters |
| Channel options | `replyTo`, custom headers matching `X-[A-Za-z0-9-]+`, single line, at most 500 characters | `senderId` | `collapseKey`, `ttlSeconds` |
| Ambiguous outcome | `DELIVERY_UNKNOWN`, wait for callback, then `lookup`; retry only on "not received" | Same | Retry like a transient failure (the provider collapses duplicates) |
| Permanent failure signals | `hard_bounce` (adds suppression), `failed`, `rejected` | `UNDELIV`, `REJECTD` | `token_invalid` (suppresses the token), `failed` |
| Engagement events | `open`, `click`, `unsubscribe` (clears marketing opt-in) | none | `opened`, `clicked` |
| Why duplicates matter | Visible and reputational | Cost and annoyance | Collapse on the device |

The limits come from `messaging.templates.*` (`MessagingProperties.Templates`): `maxVariables` 50, `maxVariableLength` 2000, `maxBodyLength` 10000, `maxRenderedEmailLength` 200000, `maxSmsSegments` 10, `maxPushTitleLength` 65, `maxPushBodyLength` 240, `maxSubjectLength` 255.

## 9.2 Email workflow

1. **Submit** with `channel=EMAIL`, a template whose channel is EMAIL, and variables. The submit transaction render-checks the template (output discarded) so a missing variable fails fast with `TEMPLATE_VARIABLE_MISSING` and nothing is persisted.
2. **Dispatch.** The consumer renders the pinned template again, HTML-escaping values, and calls the email provider with the subject, body and options.
3. **Receipts.** The simulated email vendor posts events with the payload shape `{"event","message_id","event_id","timestamp","meta":{"idempotencyRef"},"reason"}`. `delivered` becomes `DELIVERED`; `hard_bounce` becomes `BOUNCED` (status `FAILED`, address suppressed); `soft_bounce` becomes `DEFERRED` (rank 1, no state change); `open` and `click` are engagement only.
4. **Unsubscribe.** An `unsubscribe` event is recorded and clears the recipient's marketing opt-in, so the next MARKETING message is rejected `OPTED_OUT`.

```json
{"event":"delivered","message_id":"em-77ab","event_id":"evt-1001","timestamp":"2026-10-18T10:15:30Z","meta":{"idempotencyRef":"3f2c...:1"}}
```

## 9.3 SMS workflow

SMS adds **segment arithmetic** to authoring: a message that fits 160 GSM-7 characters is one segment, a longer one is `ceil(septets / 153)` segments, and any character outside the GSM alphabet (for example an emoji) switches the whole message to UCS-2 with 70 and 67 limits. The renderer computes this at validation time and rejects a body that would need more than ten segments. Phone numbers are stored in E.164 form (`phone_e164`) and masked in every response (`+1555***0101`).

Vendor status words are carrier-style: `ACCEPTD`, `SENT`, `DEFERRD`, `DELIVRD`, `UNDELIV`, `REJECTD` (simulated SMS) or `queued`, `sent`, `ok`, `fail` (Acme SMS, whose timestamp is epoch **seconds**). Carrier receipts can arrive late, which is why `SENT_TO_PROVIDER` without a receipt after `receipt-timeout` (15 minutes) becomes `DELIVERY_UNKNOWN` rather than failing.

## 9.4 Push workflow

Push targets a device token. Two properties make it forgiving:

- **Idempotent collapse.** `collapseKey` lets a newer notification replace an older pending one on the device, and the provider deduplicates on the idempotency reference, so after a timeout the platform can safely retry.
- **Token lifecycle.** A `token_invalid` receipt is a permanent failure with reason `TOKEN_INVALID` and suppresses that recipient's push token, so the platform stops sending to a dead device.

`channelOptions.push.ttlSeconds` bounds how long the vendor holds an undelivered notification.

## 9.5 Fallback between channels

A request may list `fallbackChannels` (for example `["SMS"]` behind an EMAIL primary). Fallback follows strict rules:

1. It is attempted only after `PERMANENT_FAILURE` or exhausted retries of the primary channel.
2. Each fallback channel is evaluated through the **full** policy chain independently; fallback never bypasses consent, suppression or quiet hours (`PolicyService.evaluateFallbacks`).
3. The fallback gets a **fresh attempt budget** and a new idempotency reference group (`messageId:2`, `messageId:3`, ...), recorded as `channel_used` on the same message.
4. Chains are individually legal transitions: `PROCESSING -> FAILED -> QUEUED` after a permanent failure; `PROCESSING -> RETRY_PENDING -> QUEUED` after exhausted retries.

## 9.6 What the console shows

`MessageDetail` never contains rendered text or a full address. It carries the masked address (`r***@example.test`), `channelUsed`, the attempt list (provider id, outcome, provider message id, error code, latency) and the delivery events. The detail page renders the merged **history** (`GET /messages/{id}/history`: audit events, attempts and delivery events ordered in time) as the timeline widget.

## 9.7 Tests that prove it

| Test | Proves |
|---|---|
| `TemplateRendererTest` | HTML escaping, subject CR/LF stripping, GSM-7 and UCS-2 segment counts, no expression evaluation |
| `ChannelDispatchIT` | Each channel dispatches end to end with its content rules |
| `SimulatedProviderTest` | Hard bounce address pattern, provider-side dedupe |
| `EventNormalizerTest` | Per-vendor status mapping, unknown status rejected |
| `ProviderOutcomeIT` | PUSH ambiguous is retried, EMAIL and SMS ambiguous become unknown |
| `FailureRecoveryIT#scenario04_*` | Ambiguous handling per channel, PUSH dedupe on the idempotency reference |

## Reference material

{{embed:docs/architecture/callbacks-normalization.md|title=Callback normalisation: vendor payloads and mapping|only=Vendor payloads and mapping}}
