# Context: API
Load when: adding/changing endpoints, DTOs, errors. Authority: CONTRACT section 5 and `messaging-api-v1.yaml`. Standards: `standards/api-design.md`.

- Base `/api/v1`, JSON, `ApiError{timestamp,status,error,code,message,path,correlationId,details[]}`.
- Auth: Bearer JWT (RS256), claims `sub`, `roles[]`, `tenant`, `scope`. Exceptions: `/api/v1/callbacks/**` (HMAC), health, prometheus (network-restricted), `/dev/token` (local/test only), swagger (local).
- Tenant isolation: foreign-tenant resource => 404 (never 403). `PLATFORM_ADMIN` (`tenant=*`) may use `X-Tenant-Id`.
- `POST /messages` requires `Idempotency-Key`; 202 + Location; replay => 200 + `Idempotent-Replay: true`; reuse with different body => 422 `IDEMPOTENCY_KEY_REUSED`; in-flight => 409 `IDEMPOTENCY_IN_PROGRESS`.
- Responses never include rendered body or full address (masked: `r***@example.test`, `+1555***0101`).
- Limits: body <= 256 KB (413), rate 50 rps burst 100 (429 + `Retry-After`), page size <= 200.
- Headers: `X-Correlation-Id`, `X-RateLimit-*`, `API-Version: 1`.
- Error codes: VALIDATION_FAILED, MALFORMED_REQUEST, UNSUPPORTED_CHANNEL, UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, IDEMPOTENCY_KEY_REUSED, IDEMPOTENCY_IN_PROGRESS, ILLEGAL_STATE_TRANSITION, TEMPLATE_NOT_FOUND, TEMPLATE_VARIABLE_MISSING, TEMPLATE_RENDER_ERROR, RECIPIENT_NOT_FOUND, POLICY_SUPPRESSED (unused: policy rejections are 202 + status REJECTED), RATE_LIMITED, PAYLOAD_TOO_LARGE, CONFLICT_OPTIMISTIC_LOCK, DUPLICATE_RESOURCE (409, unique-constraint conflicts), INVALID_SIGNATURE, REPLAYED_EVENT (unused: a replayed callback is 200 `replayed`), INTERNAL_ERROR, SERVICE_UNAVAILABLE.
- Add endpoint = controller + DTO records + role rule + tenant scoping + OpenAPI entry + error mapping + tests (happy, 401, 403, cross-tenant 404, validation).
- Additive changes only within v1.
