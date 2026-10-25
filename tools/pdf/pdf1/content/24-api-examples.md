# 24. API Examples

> [!IMPORTANT] Where these examples come from
> The request and response bodies below are shaped exactly by the committed OpenAPI specification (`docs/api/openapi.yaml`, identical to `backend/src/main/resources/openapi/messaging-api-v1.yaml`) and by the behaviour recorded in `docs/design/DEVIATIONS.md`. Where the specification carries an example (submit request, accepted response, error bodies, template create, preferences update, dev token) it is reproduced verbatim; other bodies are built from the schemas with illustrative values (ids, timestamps). They are **not captured from a run** of the service unless `docs/validation/e2e-run-output.txt` exists and says so; `OpenApiContractIT` is the test that keeps the served API and the specification in step.

### Behaviours confirmed by the executed end-to-end run

The executed run in `docs/validation/e2e-run-output.txt` (Appendix E: 254 passed, 0 failed, 0 skipped, real HTTP against the packaged jar on profile `local`) confirms the status codes and error codes used in the examples below. Selected PASS lines:

| Example | Confirming line in the run |
|---|---|
| 24.4 Submit | `email accepted (202)`, `202 carries Location header`, `202 carries correlation id header`, `API-Version header present` |
| 24.5 Duplicate | `same key + same body replays original (200 + Idempotent-Replay: true)`, `same key + different body -> 422 IDEMPOTENCY_KEY_REUSED`, `8 concurrent identical submissions yield exactly one message id` |
| 24.6 Policy rejection | `opted-out recipient: marketing accepted as REJECTED (202)`, `rejection reason is OPTED_OUT and message is terminal` |
| 24.7 Malformed | `malformed JSON -> 400 MALFORMED_REQUEST`, `error body does not leak stack/class names` |
| 24.8 Unsupported channel | `unsupported channel -> 400 UNSUPPORTED_CHANNEL` |
| 24.9 Unauthorized | `no token -> 401 UNAUTHENTICATED`, `tampered signature -> 401`, `AUDITOR cannot submit -> 403 FORBIDDEN` |
| 24.10 Validation | `missing fields -> 400 VALIDATION_FAILED with field details`, `missing template variables -> 422 TEMPLATE_VARIABLE_MISSING`, `unknown recipient -> 422 RECIPIENT_NOT_FOUND`, `body > 256 KB -> 413 PAYLOAD_TOO_LARGE` |
| 24.11 Cancel | `cancel a scheduled message -> 200 CANCELLED`, `cancel again -> 409 ILLEGAL_STATE_TRANSITION` |
| 24.13 Templates | `author cannot approve own version -> 403`, `different approver publishes v1`, `v1 RETIRED and v2 PUBLISHED`, `the already accepted message keeps version 1` |
| 24.14 Preferences | `stale preference write -> 409 CONFLICT_OPTIMISTIC_LOCK` |
| 24.15 Callbacks | `same X-Event-Id again -> 200 replayed`, `wrong secret -> 401 INVALID_SIGNATURE`, `late SENT after DELIVERED is out_of_order and ignored`, `BOUNCED after DELIVERED is a conflict; first terminal state wins`, `event for an unknown message -> 202 unknown_message` |
| 24.16 Bulk | `synthetic 500-row job accepted (202) with Location`, `processed = succeeded + failed + duplicate`, `restarted job completes all rows exactly once (succeeded = 20000, no duplicates)` |
| 24.17 Rate limit | `burst of 400 parallel requests triggers 429 RATE_LIMITED`, `429 carries Retry-After` |

The bodies printed in this chapter are still built from the OpenAPI schemas; the run output records pass or fail per check, not response bodies.

## 24.1 Conventions

| Item | Value |
|---|---|
| Base path | `/api/v1` (local port 18080) |
| Auth | `Authorization: Bearer <JWT>`; callbacks use HMAC instead |
| Content type | `application/json`; errors share the `ApiError` shape |
| Correlation | `X-Correlation-Id` accepted or generated, echoed in headers and error bodies |
| Pagination | `page`, `size` (at most 200), `sort`; `PageResponse {content, page, size, totalElements, totalPages, sort}` |
| API version header | `API-Version: 1` |
| Local token | `POST /dev/token` with `{"subject":"demo-sender","tenant":"tenant-retail","roles":["SENDER"],"scopes":["messages:bulk"],"ttlSeconds":3600}`; roles are sent **without** the `ROLE_` prefix; only on profiles `local` and `test` |

`ApiError` fields: `timestamp`, `status`, `error`, `code`, `message`, `path`, `correlationId`, `details[{field, message}]`.

## 24.2 Endpoint catalogue

| Method and path | Roles | Success |
|---|---|---|
| `POST /messages` | SENDER, TENANT_ADMIN, PLATFORM_ADMIN | 202 (+ `Location`); 200 on idempotent replay |
| `GET /messages`, `GET /messages/{id}` | any authenticated | 200 |
| `GET /messages/{id}/status`, `/history` | any authenticated | 200 |
| `POST /messages/{id}/cancel` | SENDER, TENANT_ADMIN, OPERATOR, PLATFORM_ADMIN | 200 or 409 |
| `POST /messages/{id}/requeue` | OPERATOR, PLATFORM_ADMIN | 200 |
| `GET /dead-letters`, `POST /dead-letters/{id}/replay`, `/discard` | OPERATOR (view also TENANT_ADMIN), PLATFORM_ADMIN | 200 |
| `GET /failed-messages` | OPERATOR, TENANT_ADMIN, PLATFORM_ADMIN | 200 |
| `GET /delivery-events` | OPERATOR, AUDITOR, TENANT_ADMIN, PLATFORM_ADMIN | 200 |
| `GET /audit-events` | AUDITOR, TENANT_ADMIN, PLATFORM_ADMIN | 200 |
| `GET/POST /templates`, `GET /templates/{id}`, `/history` | any (create: TEMPLATE_AUTHOR, TENANT_ADMIN) | 200, 201 |
| `POST /templates/{id}/versions`, `PUT .../versions/{n}?locale=` | TEMPLATE_AUTHOR (create also TENANT_ADMIN) | 201, 200 |
| `POST .../versions/{n}/submit`, `/approve`, `/reject`, `/retire` | AUTHOR; APPROVER; APPROVER; APPROVER or TENANT_ADMIN | 200 |
| `POST /templates/preview` | TEMPLATE_AUTHOR, TENANT_ADMIN, OPERATOR | 200 |
| `GET /recipients`, `GET/PUT /recipients/{id}/preferences` | see Chapter 11 | 200 |
| `POST /recipients/{id}/suppressions`, `DELETE .../{channel}` | TENANT_ADMIN, OPERATOR | 201/200 |
| `GET /providers`, `PUT /providers/{id}/config`, `PUT .../fault-mode` | OPERATOR+; PLATFORM_ADMIN; PLATFORM_ADMIN (local, test) | 200 |
| `GET /ops/queues`, `GET /ops/summary`, `POST /ops/consumers/pause`, `/resume` | OPERATOR, PLATFORM_ADMIN; any; PLATFORM_ADMIN | 200 |
| `POST /bulk-jobs`, `POST /bulk-jobs/generate-synthetic`, `GET /bulk-jobs[/{id}[/failures]]`, `POST .../stop`, `.../restart` | SENDER, TENANT_ADMIN, PLATFORM_ADMIN + scope `messages:bulk` | 202, 200 |
| `GET /diagnostics/messages/{id}` | OPERATOR, PLATFORM_ADMIN | 200 |
| `POST /callbacks/{providerId}` | HMAC | 200 (replayed), 202 |
| `GET /me` | any | 200 |

## 24.3 Error codes

| Code | HTTP | Meaning |
|---|---|---|
| `VALIDATION_FAILED` | 400 | Field-level validation; see `details` |
| `MALFORMED_REQUEST` | 400 | Unparseable body or callback |
| `UNSUPPORTED_CHANNEL` | 400 | Channel not EMAIL, SMS or PUSH |
| `UNAUTHENTICATED` | 401 | Missing, invalid or expired token |
| `INVALID_SIGNATURE` | 401 | Callback HMAC problem (uniform for all causes) |
| `FORBIDDEN` | 403 | Authenticated but not permitted |
| `NOT_FOUND` | 404 | Missing, or owned by another tenant |
| `IDEMPOTENCY_IN_PROGRESS` | 409 | First request with this key still in flight |
| `ILLEGAL_STATE_TRANSITION` | 409 | Action not allowed in the current status |
| `CONFLICT_OPTIMISTIC_LOCK` | 409 | Stale `version` on a preference or template write |
| `DUPLICATE_RESOURCE` | 409 | Unique conflict (additive code) |
| `REPLAYED_EVENT` | 409 | Defined; **unused** (replays answer 200) |
| `IDEMPOTENCY_KEY_REUSED` | 422 | Same key, different request body |
| `TEMPLATE_NOT_FOUND`, `TEMPLATE_VARIABLE_MISSING`, `TEMPLATE_RENDER_ERROR` | 422 | Template problems |
| `RECIPIENT_NOT_FOUND` | 422 | Unknown recipient in this tenant |
| `POLICY_SUPPRESSED` | 422 | Defined; **not returned by submission** (policy rejections answer 202 `REJECTED`) |
| `PAYLOAD_TOO_LARGE` | 413 | Body over 256 KB |
| `RATE_LIMITED` | 429 | Per-client token bucket empty; `Retry-After`, `X-RateLimit-*` |
| `INTERNAL_ERROR` | 500 | Unexpected |
| `SERVICE_UNAVAILABLE` | 503 | Backpressure or dependency outage; honour `Retry-After` |

## 24.4 Submit a message

Request (`POST /api/v1/messages`, headers `Authorization`, `Idempotency-Key: order-8841-confirm-v1`). This is the example in the specification:

```json
{
  "clientReference": "order-8841-confirm",
  "recipientId": "rcp-001",
  "channel": "EMAIL",
  "category": "TRANSACTIONAL",
  "priority": "NORMAL",
  "template": { "code": "TRADE_CONFIRMATION", "locale": "en-US", "version": null },
  "variables": { "accountLast4": "1234", "quantity": "10", "symbol": "ACME", "price": "250.00" },
  "fallbackChannels": ["SMS"],
  "schedule": { "sendAt": "2026-11-01T14:00:00Z", "expiresAt": "2026-11-02T14:00:00Z" }
}
```

Response `202 Accepted` with `Location: /api/v1/messages/6f1c2f64-2f0e-4c7e-9a52-0a5d1a3b9c11` (the specification's example; a future `sendAt` keeps the message `VALIDATED` until due):

```json
{
  "id": "6f1c2f64-2f0e-4c7e-9a52-0a5d1a3b9c11",
  "status": "VALIDATED",
  "channel": "EMAIL",
  "correlationId": "0b8f4a0e-6a77-4a5e-8d52-c3a1f7b2d9aa",
  "scheduledAt": null,
  "statusReason": null,
  "links": { "self": "/api/v1/messages/6f1c2f64-2f0e-4c7e-9a52-0a5d1a3b9c11" }
}
```

## 24.5 Duplicate submission

The same request with the same `Idempotency-Key` returns **200** with header `Idempotent-Replay: true` and the **original body** (same `id`, no new rows):

```json
{
  "id": "6f1c2f64-2f0e-4c7e-9a52-0a5d1a3b9c11",
  "status": "VALIDATED",
  "channel": "EMAIL",
  "correlationId": "0b8f4a0e-6a77-4a5e-8d52-c3a1f7b2d9aa",
  "scheduledAt": null,
  "statusReason": null,
  "links": { "self": "/api/v1/messages/6f1c2f64-2f0e-4c7e-9a52-0a5d1a3b9c11" }
}
```

The same key with a **different** body is `422`:

```json
{
  "timestamp": "2026-11-01T14:00:03Z",
  "status": 422,
  "error": "Unprocessable Entity",
  "code": "IDEMPOTENCY_KEY_REUSED",
  "message": "Idempotency-Key was already used with a different request body",
  "path": "/api/v1/messages",
  "correlationId": "5d9c1d1e-86a1-4f0b-9d1f-2a33d3b0f1c4"
}
```

## 24.6 Policy rejection (implemented as 202, not 4xx)

A marketing message to a recipient who has not opted in is persisted and answered `202`:

```json
{
  "id": "b2d1f0aa-3c4e-4f88-9a0b-6f7d2e1c8a55",
  "status": "REJECTED",
  "channel": "EMAIL",
  "correlationId": "7c1f3b22-0d1e-4a6e-8b59-9d2a1c4f6e10",
  "scheduledAt": null,
  "statusReason": "OPTED_OUT",
  "links": { "self": "/api/v1/messages/b2d1f0aa-3c4e-4f88-9a0b-6f7d2e1c8a55" }
}
```

## 24.7 Malformed request

A body that is not valid JSON:

```json
{
  "timestamp": "2026-11-01T14:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "code": "MALFORMED_REQUEST",
  "message": "Request body is missing or is not valid JSON",
  "path": "/api/v1/messages",
  "correlationId": "0b8f4a0e-6a77-4a5e-8d52-c3a1f7b2d9aa"
}
```

## 24.8 Unsupported channel

This is the specification's example for the `BadRequest` response:

```json
{
  "timestamp": "2026-11-01T14:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "code": "UNSUPPORTED_CHANNEL",
  "message": "Unsupported channel; supported channels are EMAIL, SMS, PUSH",
  "path": "/api/v1/messages",
  "correlationId": "0b8f4a0e-6a77-4a5e-8d52-c3a1f7b2d9aa",
  "details": [ { "field": "channel", "message": "must be one of EMAIL, SMS, PUSH" } ]
}
```

## 24.9 Unauthorized and forbidden

No token, an expired token, or a token that fails RS256 validation:

```json
{
  "timestamp": "2026-11-01T14:00:00Z",
  "status": 401,
  "error": "Unauthorized",
  "code": "UNAUTHENTICATED",
  "message": "Authentication is required",
  "path": "/api/v1/messages",
  "correlationId": "0b8f4a0e-6a77-4a5e-8d52-c3a1f7b2d9aa"
}
```

A `SENDER` calling an operator endpoint (for example `GET /api/v1/ops/queues`) receives `403 FORBIDDEN` with the same shape. A message owned by another tenant is `404 NOT_FOUND`, not 403.

## 24.10 Validation error

A request that fails field validation, such as an SMS with an invalid priority and no template code:

```json
{
  "timestamp": "2026-11-01T14:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "code": "VALIDATION_FAILED",
  "message": "Request validation failed",
  "path": "/api/v1/messages",
  "correlationId": "0b8f4a0e-6a77-4a5e-8d52-c3a1f7b2d9aa",
  "details": [
    { "field": "template.code", "message": "must not be blank" },
    { "field": "priority", "message": "must be one of CRITICAL, HIGH, NORMAL, LOW" }
  ]
}
```

A missing template variable is a `422 TEMPLATE_VARIABLE_MISSING` and nothing is persisted.

## 24.11 Cancel

`POST /api/v1/messages/{id}/cancel` while the message is still `VALIDATED`, `QUEUED` or `RETRY_PENDING` returns the lightweight status:

```json
{
  "id": "6f1c2f64-2f0e-4c7e-9a52-0a5d1a3b9c11",
  "status": "CANCELLED",
  "statusReason": null,
  "channelUsed": null,
  "attemptCount": 0,
  "updatedAt": "2026-11-01T14:00:05Z",
  "terminal": true
}
```

After delivery the same call is `409` (the specification's example for `Conflict`):

```json
{
  "timestamp": "2026-11-01T14:00:00Z",
  "status": 409,
  "error": "Conflict",
  "code": "ILLEGAL_STATE_TRANSITION",
  "message": "Action 'cancel' is not permitted while message is DELIVERED",
  "path": "/api/v1/messages/6f1c2f64/cancel",
  "correlationId": "0b8f4a0e-6a77-4a5e-8d52-c3a1f7b2d9aa"
}
```

## 24.12 Status and detail

`GET /api/v1/messages/{id}/status` (lightweight, built for polling):

```json
{
  "id": "6f1c2f64-2f0e-4c7e-9a52-0a5d1a3b9c11",
  "status": "SENT_TO_PROVIDER",
  "statusReason": null,
  "channelUsed": "EMAIL",
  "attemptCount": 1,
  "updatedAt": "2026-11-01T14:00:02Z",
  "terminal": false
}
```

`GET /api/v1/messages/{id}` returns `MessageDetail`: masked address only, never the rendered body:

```json
{
  "id": "6f1c2f64-2f0e-4c7e-9a52-0a5d1a3b9c11",
  "tenantId": "tenant-retail",
  "clientReference": "order-8841-confirm",
  "recipient": { "id": "rcp-001", "externalRef": "CUST-001", "maskedAddress": "r***@example.test" },
  "channel": "EMAIL",
  "channelUsed": "EMAIL",
  "category": "TRANSACTIONAL",
  "priority": "NORMAL",
  "status": "DELIVERED",
  "statusReason": null,
  "terminal": true,
  "template": { "code": "TRADE_CONFIRMATION", "versionNo": 3, "locale": "en-US" },
  "correlationId": "0b8f4a0e-6a77-4a5e-8d52-c3a1f7b2d9aa",
  "idempotencyKey": "order-8841-confirm-v1",
  "scheduledAt": null,
  "expiresAt": "2026-11-02T14:00:00Z",
  "attemptCount": 1,
  "maxAttempts": 5,
  "nextAttemptAt": null,
  "createdAt": "2026-11-01T14:00:00Z",
  "updatedAt": "2026-11-01T14:00:04Z",
  "attempts": [
    { "attemptNo": 1, "channel": "EMAIL", "providerId": "simulated-email", "outcome": "ACCEPTED",
      "providerMessageId": "em-77ab", "errorCode": null, "latencyMs": 42, "startedAt": "2026-11-01T14:00:01Z" }
  ],
  "deliveryEvents": [
    { "id": "e1", "messageId": "6f1c2f64-2f0e-4c7e-9a52-0a5d1a3b9c11", "providerId": "simulated-email",
      "providerEventId": "evt-1001", "type": "DELIVERED", "occurredAt": "2026-11-01T14:00:03Z",
      "receivedAt": "2026-11-01T14:00:04Z", "applied": true, "ignoredReason": null }
  ]
}
```

`GET /api/v1/messages/{id}/history` returns the merged timeline of `TimelineEntry {at, kind (AUDIT, ATTEMPT or DELIVERY_EVENT), type, summary, detail: object}`.

## 24.13 Templates

Create a template and its first `DRAFT` version (`POST /api/v1/templates`, the specification's example):

```json
{
  "code": "PASSWORD_RESET",
  "name": "Password reset",
  "channel": "EMAIL",
  "category": "SECURITY_ALERT",
  "locale": "en",
  "subject": "Reset your password",
  "body": "<p>Use code {{code}} to reset your password. It expires in {{minutes|15}} minutes.</p>",
  "variables": ["code", "minutes"]
}
```

Preview without sending (`POST /api/v1/templates/preview`):

```json
{ "channel": "EMAIL", "subject": "Reset your password",
  "body": "<p>Use code {{code}} to reset your password. It expires in {{minutes|15}} minutes.</p>",
  "variables": ["code", "minutes"], "sampleData": { "code": "482913" }, "locale": "en" }
```

```json
{ "valid": true, "subject": "Reset your password",
  "body": "<p>Use code 482913 to reset your password. It expires in 15 minutes.</p>",
  "html": true, "sms": null, "errors": [] }
```

Approval is a state change on one version: `POST /api/v1/templates/{id}/versions/1/approve?locale=en` by a `TEMPLATE_APPROVER` who is not the author returns the `TemplateVersion` with `status: PUBLISHED`, `approvedBy` and `publishedAt` set; the same call by the author is refused.

## 24.14 Preferences

`PUT /api/v1/recipients/{id}/preferences` (the specification's example; `version` must match the stored version):

```json
{
  "marketingOptIn": true,
  "emailEnabled": true,
  "smsEnabled": true,
  "pushEnabled": false,
  "quietStart": "22:00",
  "quietEnd": "07:00",
  "timeZone": "America/New_York",
  "fallbackOrder": ["SMS", "EMAIL"],
  "version": 3
}
```

A stale `version` yields `409 CONFLICT_OPTIMISTIC_LOCK`; the console shows a conflict dialog instead of overwriting.

## 24.15 Callbacks

A signed provider callback (`POST /api/v1/callbacks/simulated-email`). Headers: `X-Signature: t=1767225600,v1=<hmac hex>`, `X-Provider-Key-Id: simulated-email-key`, `X-Event-Id: evt-1001`. The body is vendor specific:

```json
{"event":"delivered","message_id":"em-77ab","event_id":"evt-1001","timestamp":"2026-10-18T10:15:30Z","meta":{"idempotencyRef":"3f2c...:1"}}
```

Responses (`CallbackAck {result, eventId}`):

| Case | Status | Body |
|---|---|---|
| Applied | 202 | `{"result":"accepted","eventId":"evt-1001"}` |
| Same `X-Event-Id` again | **200** | `{"result":"replayed","eventId":"evt-1001"}` |
| Same vendor event under another transport id | 202 | `{"result":"duplicate", ...}` |
| Receipt after the outcome is known | 202 | `{"result":"out_of_order", ...}` |
| Unknown message | 202 | `{"result":"unknown_message", ...}` |
| Bad signature, stale `t`, unknown provider, wrong key | 401 | `INVALID_SIGNATURE` |
| Unknown vendor status, missing event id | 400 | `MALFORMED_REQUEST` |

## 24.16 Bulk jobs

Create from a CSV (`POST /api/v1/bulk-jobs`, multipart: `file`, `name`, `templateCode`, `channel`, `category`, `ratePerSecond`). The caller needs scope `messages:bulk`. Response `202` with a `Location` header:

```json
{
  "id": "9a7e5c10-1b2d-4e3f-8a6b-0c9d8e7f6a51",
  "tenantId": "tenant-retail",
  "name": "october-newsletter",
  "status": "CREATED",
  "total": 0, "processed": 0, "succeeded": 0, "failed": 0, "duplicate": 0,
  "fileName": "9a7e5c10-1b2d-4e3f-8a6b-0c9d8e7f6a51.csv",
  "ratePerSecond": 50,
  "createdBy": "demo-sender",
  "startedAt": null,
  "finishedAt": null
}
```

Synthetic job without an upload (`POST /api/v1/bulk-jobs/generate-synthetic`):

```json
{ "rows": 1000, "duplicateRate": 0.02, "invalidRate": 0.01,
  "templateCode": "WELCOME", "channel": "EMAIL", "category": "SERVICE" }
```

A finished job reconciles as `processed = succeeded + failed + duplicate`; `GET /api/v1/bulk-jobs/{id}/failures` lists `{lineNo, reason, rawLineHash}` and a `lineNo` of `0` is a job-level failure such as `SKIP_LIMIT_EXCEEDED`.

## 24.17 Backpressure and rate limiting

When the dispatch backlog is full the API answers 503 with a `Retry-After` header; the client retries with the **same** `Idempotency-Key`. When the per-client token bucket is empty it answers 429 with `Retry-After`, `X-RateLimit-Limit`, `X-RateLimit-Remaining` and `X-RateLimit-Reset`:

```json
{
  "timestamp": "2026-11-01T14:00:00Z",
  "status": 503,
  "error": "Service Unavailable",
  "code": "SERVICE_UNAVAILABLE",
  "message": "Dispatch backlog is full (5000 waiting); retry later",
  "path": "/api/v1/messages",
  "correlationId": "0b8f4a0e-6a77-4a5e-8d52-c3a1f7b2d9aa"
}
```

## 24.18 Reproducing these locally

```bash
bash scripts/run-local.sh --wait      # profile local, API on http://localhost:18080
bash scripts/seed-demo.sh             # synthetic demo data
bash scripts/smoke-test.sh            # liveness, readiness, 401 on /api/v1/me
node tests/failure-recovery/callback-replay.mjs   # scripted replay/duplicate callbacks
```

`tests/api-http` request collections, where present, and the OpenAPI spec at `docs/api/openapi.yaml` (also served at `/v3/api-docs` and `/swagger-ui.html` on the local profile) are the executable reference.

