# API Contract Guide

Authoritative spec: `backend/src/main/resources/openapi/messaging-api-v1.yaml` (copy at `docs/api/openapi.yaml`). Endpoint list and roles: CONTRACT section 5. This guide explains conventions and the change policy.

## Conventions
| Topic | Rule |
|---|---|
| Base path | `/api/v1`; `API-Version: 1` response header |
| Media type | `application/json`; errors as `ApiError` |
| Auth | Bearer JWT; callbacks use HMAC |
| IDs | UUID strings; opaque to clients |
| Timestamps | ISO-8601 UTC (`Z`) |
| Enums | UPPER_SNAKE; unknown values rejected `MALFORMED_REQUEST`; clients must tolerate NEW enum values in responses |
| Pagination | `page` (0-based), `size` (<= 200), `sort=field,dir`; `PageResponse` |
| Correlation | send `X-Correlation-Id` (<= 64 chars, `[A-Za-z0-9-_.]`) or receive generated; echoed |
| Idempotency | `Idempotency-Key` required on `POST /messages`; unique per logical send |
| Rate limiting | `X-RateLimit-*`; 429 + `Retry-After` |
| Async semantics | 202 means accepted, not sent. Poll status or use callbacks (future: webhooks to clients not provided) |
| Not-found policy | foreign-tenant resources return 404 |

## Error handling for clients
| HTTP | code | Client action |
|---|---|---|
| 400 | `VALIDATION_FAILED`, `MALFORMED_REQUEST`, `TEMPLATE_VARIABLE_MISSING`, `UNSUPPORTED_CHANNEL` | fix request; do not retry |
| 401 | `UNAUTHENTICATED` | refresh token |
| 403 | `FORBIDDEN` | needs role/scope |
| 404 | `NOT_FOUND`, `TEMPLATE_NOT_FOUND`, `RECIPIENT_NOT_FOUND` | check ids |
| 409 | `IDEMPOTENCY_IN_PROGRESS`, `ILLEGAL_STATE_TRANSITION`, `CONFLICT_OPTIMISTIC_LOCK`, `DUPLICATE_RESOURCE` (unique-constraint conflict, e.g. duplicate template code) | wait/refetch/change the identifier |
| 413 | `PAYLOAD_TOO_LARGE` | reduce |
| 422 | `IDEMPOTENCY_KEY_REUSED` | new key |

Policy rejections are **not** errors: `POST /messages` answers 202 with `status=REJECTED` and `statusReason` (the enum value `POLICY_SUPPRESSED` is unused). An idempotent replay of a submission answers 200 with `Idempotent-Replay: true`; a replayed provider callback answers 200 `{"result":"replayed"}` (`REPLAYED_EVENT` is unused).
| 429 | `RATE_LIMITED` | backoff using `Retry-After` |
| 5xx / 503 | `INTERNAL_ERROR`, `SERVICE_UNAVAILABLE` | retry with same Idempotency-Key, exponential backoff + jitter |

## Safe retry matrix
| Method | Retry on timeout? |
|---|---|
| `POST /messages` | yes, same Idempotency-Key |
| `POST /messages/{id}/cancel` | yes (idempotent: 200 or 409) |
| `PUT preferences` | after refetch (optimistic lock) |
| bulk job `POST` | no auto-retry; check `GET /bulk-jobs` first |

## Compatibility and change policy
Non-breaking: new optional request fields, new response fields, new endpoints, new enum values in responses (documented), new error codes. Breaking: removing/renaming fields, changing types/semantics, tightening validation, changing status codes, changing default behavior. Breaking changes require `/api/v2`, 6-month deprecation (`Deprecation`, `Sunset` headers; illustrative), ADR, consumer notification. Contract tests: committed OpenAPI vs springdoc path assertion test; diff check in review (`ai-engineering/standards/api-design.md`).

## Webhook (provider) contract
Not a client API: `POST /api/v1/callbacks/{providerId}`; see `docs/architecture/callback-flow.md`.

## Examples
Ready-to-run HTTP files live in `tests/api-http` (see source). Example request: CONTRACT section 5.
