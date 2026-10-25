# Feature Specification (example): Message Submission with Policy-Aware Dispatch

Spec-level document for the core feature; the spec-driven template is `specs/spec-template.md`; full examples in `specs/0001-priority-aging.md`, `specs/0002-new-provider-adapter.md`.

## Summary
Authenticated service clients submit a templated message for a recipient and channel. The platform accepts it durably (202), enforces policy, dispatches asynchronously through a provider, tracks delivery and exposes status.

## Actors
SENDER client, TENANT_ADMIN, OPERATOR, provider (callbacks).

## Behavior
1. **Accept**: validate DTO; claim idempotency; resolve recipient (tenant-scoped); evaluate policy; resolve and pin template version; validate variables; persist message (VALIDATED) + outbox + audit; respond 202 with `Location: /api/v1/messages/{id}`.
2. **Schedule**: `schedule.sendAt` future keeps VALIDATED until due; quiet hours may defer.
3. **Dispatch**: consumer re-evaluates policy, renders, rate-limits, calls provider, records attempt, applies outcome (see `docs/architecture/dispatch-retry-flow.md`).
4. **Status**: `GET /messages/{id}/status` returns lightweight status; `/history` returns merged timeline.
5. **Cancel**: allowed in VALIDATED, QUEUED, RETRY_PENDING; else 409.
6. **Fallback**: optional `fallbackChannels`, each independently policy-checked.

## Inputs and validation
| Field | Rule |
|---|---|
| `recipientId` xor `recipient.externalRef` | exactly one; must exist in tenant |
| `channel` | EMAIL/SMS/PUSH; recipient must have a valid address for it |
| `category`, `priority` | enums; category must match template category |
| `template.code`, `locale`, `version?` | PUBLISHED version resolved and pinned |
| `variables` | all required present; no unknown; length limits; string values only (see source) |
| `schedule.sendAt` | future, <= max horizon (config); `expiresAt` > `sendAt` |
| body size | <= 256 KB |
| header `Idempotency-Key` | required, 1-128 chars [A-Za-z0-9_-:.] |

## Outputs
`MessageAcceptedResponse {id,status,channel,correlationId,scheduledAt,links}`; errors via `ApiError` codes in CONTRACT section 5.

## Edge cases
| Case | Behavior |
|---|---|
| Duplicate request | original response + `Idempotent-Replay: true` |
| Same key, different body | 422 `IDEMPOTENCY_KEY_REUSED` |
| Recipient opted out (marketing) | 202 with status REJECTED, `statusReason=OPTED_OUT` |
| Hard-suppressed address (tenant scoped) | 202 with status REJECTED, `statusReason=SUPPRESSED` |
| Template RETIRED after accept | still renders (pinned) |
| Provider timeout | `DELIVERY_UNKNOWN` then reconciliation |
| Expired before dispatch | not sent; terminal per source |
| Backpressure | 503 + `Retry-After` |

## Telemetry
`messaging.messages.accepted`, `messaging.transitions`, `messaging.dispatch.delay`, `messaging.provider.calls`.

## Security
Tenant from JWT; masked responses; no content at rest; audit all transitions.

## Acceptance
See `acceptance-criteria.md`.
