# REST API Design and Error Handling

A reference for API design questions, using the messaging reference implementation's API (CONTRACT section 5, ADR-backed decisions) as the worked example. The platform is a reference implementation; nothing here asserts that endpoints were load-tested or deployed.

## Kid-friendly analogies

| Concept | Analogy |
|---|---|
| Idempotency key | A raffle ticket stub for your order. If you press the "order" button twice because the screen froze, the kitchen sees the same stub number and makes one pizza, not two. |
| 202 Accepted | The bakery says "we got your cake order, here is a receipt number, come check later." It does not say the cake is baked. |
| Pagination | Reading a long book a chapter at a time with a bookmark, rather than carrying the whole library. |
| Rate limit headers | A water fountain sign: "10 sips per minute, you have 3 left, the counter resets in 20 seconds." |
| Problem details error | A standard "return slip" with the same boxes every time: what happened, why, and who to call with this reference number. |
| Versioning | Keeping the old plug shape working while introducing a new one. |

## Resource design

Model nouns, not verbs; use HTTP methods for the verb. Collections are plural; identifiers are opaque.

| Practice | Example from the contract |
|---|---|
| Collection and item | `GET /api/v1/messages`, `GET /api/v1/messages/{id}` |
| Create returns 202 plus `Location` when processing is asynchronous | `POST /api/v1/messages` |
| Sub-resources for owned data | `/recipients/{id}/preferences`, `/templates/{id}/versions` |
| State-changing actions that are not CRUD use a sub-resource verb | `POST /messages/{id}/cancel`, `POST /dead-letters/{messageId}/replay` |
| Lightweight status endpoint for polling | `GET /messages/{id}/status` returns only `{id,status,statusReason,channelUsed,attemptCount,updatedAt,terminal}` |
| Filters as query parameters | `status`, `channel`, `from`, `to`, `page`, `size`, `sort` |

Action endpoints (`cancel`, `requeue`, `submit`, `approve`) are a pragmatic exception: the alternative (patching a `status` field) lets a client attempt any transition. Posting an action lets the server enforce the state machine (CONTRACT section 3.1) and return `409 ILLEGAL_STATE_TRANSITION` for illegal ones.

### Do not leak internals

Return DTO records, never entities. The contract states `MessageDetail` never returns the rendered body or full address; only masked addresses. Tenant isolation returns 404 rather than 403 for another tenant's resources, so existence is not disclosed (CONTRACT section 5).

## HTTP methods and semantics

| Method | Safe | Idempotent | Notes |
|---|---|---|---|
| GET | Yes | Yes | Cacheable, no body semantics |
| POST | No | No (unless you add a key) | Create, or action |
| PUT | No | Yes | Replace; include a version for concurrency |
| PATCH | No | Not guaranteed | Partial update; define the media type clearly |
| DELETE | No | Yes | Second call returns 404 or 204; both acceptable if documented |

## Status codes

| Code | Use | Platform example |
|---|---|---|
| 200 OK | Success with body | `GET`, cancel success, idempotent replay |
| 201 Created | Synchronous creation | Template creation |
| 202 Accepted | Work accepted, not complete | `POST /messages`, `POST /bulk-jobs` |
| 204 No Content | Success without body | Deleting a suppression |
| 400 Bad Request | Malformed JSON | `MALFORMED_REQUEST` |
| 401 Unauthorized | No or invalid credentials | `UNAUTHENTICATED` |
| 403 Forbidden | Authenticated but not allowed | `FORBIDDEN` |
| 404 Not Found | Missing, or cross-tenant | `NOT_FOUND` |
| 409 Conflict | State conflict | `ILLEGAL_STATE_TRANSITION`, `IDEMPOTENCY_IN_PROGRESS`, `CONFLICT_OPTIMISTIC_LOCK` |
| 413 Payload Too Large | Body over limit (256 KB) | `PAYLOAD_TOO_LARGE` |
| 422 Unprocessable Content | Syntactically valid but semantically rejected | `IDEMPOTENCY_KEY_REUSED`, `VALIDATION_FAILED` where applicable |
| 429 Too Many Requests | Rate limit | `RATE_LIMITED` with `Retry-After` |
| 500 Internal Server Error | Bug | `INTERNAL_ERROR` |
| 503 Service Unavailable | Backpressure or dependency down | `SERVICE_UNAVAILABLE` with `Retry-After` |

A frequent interview trap: 401 versus 403. 401 means "I do not know who you are"; 403 means "I know who you are and the answer is no". Another: do not return 200 with an error body.

## The 202 async pattern

Sending a message involves a provider call that can take seconds and can fail transiently. Making the client wait couples API latency to provider latency. The contract instead accepts, persists, and returns 202.

Flow (CONTRACT section 6): validate, claim idempotency, check the template renders, persist the message (status VALIDATED), an outbox event and an audit record in one transaction, return 202. Publishing and delivery happen afterwards.

```http
POST /api/v1/messages
Authorization: Bearer <token>
Idempotency-Key: 7c1f0a52-3b6e-4b0a-9a44-0e5d2f6a9b11
Content-Type: application/json

{ "recipientId": "rcp-001", "channel": "EMAIL", "category": "TRANSACTIONAL",
  "template": {"code": "TRADE_CONFIRMATION", "locale": "en-US"},
  "variables": {"accountLast4": "1234", "amount": "250.00"} }
```

```http
HTTP/1.1 202 Accepted
Location: /api/v1/messages/9f2c1d7e-...
X-Correlation-Id: 5d1a...
API-Version: 1

{ "id": "9f2c1d7e-...", "status": "VALIDATED", "channel": "EMAIL",
  "correlationId": "5d1a...", "links": { "self": "...", "status": "..." } }
```

The key message for the client: 202 means accepted for processing. Delivery is discovered through polling `GET /messages/{id}/status`, or later through a callback or event feed. The state `SENT_TO_PROVIDER` is provider acceptance and never implies delivery (CONTRACT section 3.1).

Polling guidance: return a `terminal` boolean so clients know when to stop, use `Retry-After` hints, and consider webhooks or server-sent events for push notification of state changes in a larger design.

## Idempotency keys

Networks retry. A client that times out cannot know whether the server processed its POST. An `Idempotency-Key` header (required for `POST /messages`, CONTRACT section 3.3 item 6) makes the retry safe.

| Situation | Behaviour |
|---|---|
| First request | Process, store the key, request hash and response |
| Same key, same request hash | Return original response, HTTP 200 plus `Idempotent-Replay: true` |
| Same key, different request hash | 422 `IDEMPOTENCY_KEY_REUSED` |
| Same key while the first is still running | 409 `IDEMPOTENCY_IN_PROGRESS` |

Design points to articulate:

- Scope the key per tenant (unique `(tenant_id, idem_key)`, CONTRACT section 4), so tenants cannot collide.
- Hash the canonical request so you can detect misuse.
- The claim must be atomic: a unique constraint is the arbiter, with Redis as a fast path only (CONTRACT section 1). If Redis is down the system falls back to the database through `ResilientCoordination`.
- Keys expire (`expires_at`); define a retention window and document it.
- Downstream, the provider also receives an idempotency reference derived from the message (CONTRACT section 6), so idempotency extends beyond the API boundary, though never to "exactly once" across a third party.

<!-- in-project:d04 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/service/NotificationService.java`
>
> The key format is validated before it is used as a database and cache key. A first submission answers `202 Accepted`; an identical replay answers `200` with `Idempotent-Replay: true`.
>
> ```java
> private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:~-]{1,128}");
> private static final Pattern HEADER_NAME = Pattern.compile("X-[A-Za-z0-9-]{1,50}");
> private static final Duration MAX_SCHEDULE_AHEAD = Duration.ofDays(366);
>
> /** @param replay true when an earlier identical request is being replayed (HTTP 200 + Idempotent-Replay). */
> public record SubmitResult(MessageAcceptedResponse response, boolean replay) {}
> ```


## Pagination, filtering and sorting

The contract returns `PageResponse<T> {content, page, size, totalElements, totalPages, sort}` with a maximum page size of 200 (CONTRACT section 5).

| Strategy | Pros | Cons |
|---|---|---|
| Offset (`page`, `size`) | Simple, random access, total count | Slow for deep pages (database scans and discards rows), unstable under concurrent inserts (duplicates or gaps) |
| Keyset or cursor (`after=<opaque>`) | Stable and fast at any depth | No random jump; needs a deterministic sort with a unique tiebreaker |

Offset is acceptable for operator consoles with bounded depth; for large append-heavy feeds such as delivery events or audit events, keyset is the better choice. See the databases chapter for query shape. Always whitelist sort fields; never pass a client string into an `ORDER BY`.

Cap `size` server-side and return a clear validation error (or clamp, documented) when exceeded.

## Versioning

| Approach | Example | Tradeoff |
|---|---|---|
| URI version | `/api/v1/...` (the contract's choice) | Visible, easy routing and caching; creates parallel trees |
| Header or media type | `Accept: application/vnd.example.v2+json` | Cleaner URLs, harder to test in a browser |
| Query parameter | `?version=2` | Easy but easily forgotten |

The contract also returns an `API-Version: 1` response header. Practical rules: additive changes (new optional fields, new endpoints, new enum values clients are told to tolerate) are non-breaking; removing or retyping fields, tightening validation, or changing semantics needs a new version. Publish a deprecation policy with a `Deprecation` or `Sunset` header and a migration window. Clients should ignore unknown fields (tolerant reader).

## Error model

The contract defines errors as `application/problem+json`-style `ApiError` (CONTRACT section 5), closely related to RFC 9457 (which obsoletes RFC 7807) problem details:

```json
{
  "timestamp": "2026-11-01T14:00:00Z",
  "status": 422,
  "error": "Unprocessable Content",
  "code": "VALIDATION_FAILED",
  "message": "Request validation failed",
  "path": "/api/v1/messages",
  "correlationId": "5d1a...",
  "details": [
    { "field": "variables.amount", "message": "must not be blank" }
  ]
}
```

| Field | Purpose |
|---|---|
| `status` | Mirrors the HTTP status for clients that lose the status line |
| `code` | Stable machine-readable code (for example `IDEMPOTENCY_KEY_REUSED`); clients branch on this, never on `message` |
| `message` | Human-readable, safe to display; no stack traces, SQL or secrets |
| `correlationId` | Joins the client report to server logs and traces (`X-Correlation-Id` header, CONTRACT section 5) |
| `details` | Field-level errors for validation |

Standard RFC 9457 members are `type`, `title`, `status`, `detail` and `instance`. The contract's shape uses its own member names; a mapping document or an additional `type` URI is a reasonable extension. Mention that you can adopt the standard naming at the boundary without changing internal code.

The contract's code list includes `VALIDATION_FAILED`, `MALFORMED_REQUEST`, `UNAUTHENTICATED`, `FORBIDDEN`, `NOT_FOUND`, `IDEMPOTENCY_KEY_REUSED`, `IDEMPOTENCY_IN_PROGRESS`, `ILLEGAL_STATE_TRANSITION`, `POLICY_SUPPRESSED`, `RATE_LIMITED`, `PAYLOAD_TOO_LARGE`, `CONFLICT_OPTIMISTIC_LOCK`, `INVALID_SIGNATURE`, `REPLAYED_EVENT`, `INTERNAL_ERROR`, `SERVICE_UNAVAILABLE` and others. As implemented (`docs/design/DEVIATIONS.md`): policy rejection at submission returns `202` with `status=REJECTED` (so `POLICY_SUPPRESSED` is not returned there), a replayed callback returns `200 {"result":"replayed"}` (so `REPLAYED_EVENT` is unused), and `DUPLICATE_RESOURCE` (409) was added for unique-constraint conflicts.

### Implementation sketch

A single `GlobalExceptionHandler` in `web/` (CONTRACT section 2) with `@RestControllerAdvice` maps exceptions to `ApiError`. Domain code throws `ApiException` subclasses carrying an `ErrorCode`; the handler translates once. This keeps controllers clean and guarantees a uniform shape, including for framework exceptions such as `MethodArgumentNotValidException` and `HttpMessageNotReadableException`, and for security failures raised before the controller (entry point and access-denied handler need the same JSON).

```java
@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handle(ApiException ex, HttpServletRequest req) {
        return ResponseEntity.status(ex.status())
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(ApiError.of(ex, req));
    }
}
```

Security of errors: do not reveal whether a username or tenant exists, do not echo input that may contain sensitive data, log details server-side with the correlation id, and return generic `INTERNAL_ERROR` for unexpected faults.

### Which errors are retryable

| Class | Client action |
|---|---|
| 4xx except 408, 409 (in-progress), 429 | Do not retry unchanged; fix the request |
| 409 `IDEMPOTENCY_IN_PROGRESS` | Retry after a short delay with the same key |
| 429, 503 | Retry with backoff, honour `Retry-After` |
| 500 | Retry only if the call is idempotent (hence the key) |

## Rate limiting headers

The contract lists `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset` and `Retry-After` on 429, with defaults of 50 requests per second and burst 100, configurable (CONTRACT section 5, section 9 `messaging.api.*`). The Redis token bucket (CONTRACT section 1) supports this across instances.

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 2
X-RateLimit-Limit: 50
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1793541602
```

Token bucket allows bursts up to capacity and a steady refill rate; fixed windows allow double-rate at window edges; sliding windows are smoother but costlier. Decide the key: per client application, per tenant, per IP. Return headers on every response, not only on 429, so clients can self-throttle. The IETF has a draft for standardised `RateLimit` header fields; mention it as emerging rather than universal. Backpressure at the queue level is separate: when queue depth exceeds `messaging.backpressure.max-queue-depth`, the API returns 503 with `Retry-After` (CONTRACT section 6).

## Concurrency control over HTTP

For editable resources use a version: ETag with `If-Match`, or a `version` field in the body. The contract uses a `version` in the preferences body and an `If-Match` or version field on template version edits; a stale write returns 409 `CONFLICT_OPTIMISTIC_LOCK`. (Some designs prefer 412 Precondition Failed when `If-Match` is used; either is defensible if documented.)

## Callbacks as an inbound API

`POST /api/v1/callbacks/{providerId}` is authenticated with an HMAC signature header `X-Signature: t=<epoch>,v1=<hex>`, with a 5 minute tolerance and a replay guard keyed by `X-Event-Id` (CONTRACT section 1). Return 2xx quickly after validation and durable recording, so providers do not retry unnecessarily; return 2xx for duplicates (idempotent receipt) and an error code for invalid signatures. Compare signatures with a constant-time comparison. Do not trust network position.

## OpenAPI

The contract commits a spec at `backend/src/main/resources/openapi/messaging-api-v1.yaml`, an exported copy at `docs/api/openapi.yaml`, and a test asserting the generated springdoc output contains every committed path (CONTRACT section 1). That is a spec-first plus drift-detection approach.

| Approach | Pro | Con |
|---|---|---|
| Spec-first (hand-written, then implement) | Design review before code, stable contract | Risk of drift unless tested |
| Code-first (annotations generate spec) | Always matches code | Spec reflects accidents; weaker design discipline |
| Hybrid with a drift test | Both | Test must be maintained |

Good specs include: `securitySchemes` (bearer JWT), reusable `components/schemas` for `ApiError` and `PageResponse`, `examples`, documented headers, and every error response per operation. Use the spec to generate client SDKs, mock servers and contract tests. Keep the dev-only endpoints (`/dev/token`, simulator endpoints) out of any production spec.

## Worked interview question: design "send a notification" API

Strong answer outline: resource is a message; POST is asynchronous and returns 202 with an id; require an idempotency key; validate synchronously only what is cheap (schema, template variables, recipient existence); persist and publish via an outbox; expose status by id and a list with filters; provide cancel as an action; return consistent problem errors with correlation id; add rate limiting with headers; secure with scoped JWT and tenant isolation; document with OpenAPI; define callbacks or events for delivery updates; version in the URI. Then discuss tradeoffs: polling versus webhooks, offset versus keyset, and what "accepted" promises.

## Key takeaways

- Model resources as nouns; use action sub-resources where a state machine must be enforced by the server.
- Return 202 for asynchronous work and make clear that accepted does not mean delivered; give clients a status resource with a `terminal` flag.
- Idempotency keys must be scoped per tenant, hash-checked, and claimed atomically through a unique constraint; define replay (200 plus `Idempotent-Replay`), reuse (422) and in-progress (409) behaviour.
- Use stable machine codes in a uniform problem-style error body with a correlation id; never leak internals; keep 401 and 403 distinct and use 404 for cross-tenant access.
- Prefer keyset pagination for large or append-heavy data; cap page size and whitelist sort fields.
- Version in the URI, evolve additively, and publish a deprecation policy.
- Send rate limit headers on every response, `Retry-After` on 429 and 503, and tell clients which errors are retryable.
- Use a spec plus a drift test so the OpenAPI document cannot silently diverge from the implementation.
