# API Design Standards

Complements `docs/leadership/api-contract-guide.md` and CONTRACT section 5.

1. **Resource-oriented**: nouns (`/messages`, `/templates`), actions as sub-resources with POST (`/messages/{id}/cancel`), consistent plural names.
2. **Async honesty**: operations that start work return 202 + `Location`; status resource is pollable; never block on providers.
3. **Idempotency**: all non-idempotent creates accept `Idempotency-Key`; actions designed idempotent (cancel returns 200 if already cancelled? no: 409 `ILLEGAL_STATE_TRANSITION` per CONTRACT; document it).
4. **Errors**: single `ApiError` shape; `code` stable and machine-readable (CONTRACT list); `message` human-readable and non-sensitive; `details[]` for field errors; always `correlationId`.
5. **Status codes**: 200/201/202 success, 400 validation, 401 unauth, 403 forbidden role, 404 not found or foreign tenant, 409 state/lock conflict, 413 too large, 422 semantic (idempotency reuse), 429 rate limit, 5xx server, 503 backpressure with `Retry-After`.
6. **Validation**: Bean Validation on records; reject unknown properties; bound strings, collections, numbers; enum parsing errors map to `MALFORMED_REQUEST`.
7. **Security by default**: every endpoint declares roles; every id-based endpoint is tenant-scoped; responses are DTOs with masked fields; no mass assignment.
8. **Pagination**: `page`, `size` (<= 200), `sort`; `PageResponse`; stable default sort; max offset guard or keyset for exports.
9. **Versioning**: `/api/v1`, additive changes only; breaking => v2 + deprecation headers.
10. **Concurrency**: optimistic locking via `version`/`If-Match`; 409 `CONFLICT_OPTIMISTIC_LOCK`.
11. **Documentation**: OpenAPI is committed and tested against springdoc output; each endpoint has summary, roles, error responses, examples.
12. **Observability**: correlation id echoed; `http_server_requests_seconds` tagged by templated URI (never raw ids).
13. **Naming**: camelCase JSON, UPPER_SNAKE enums, ISO-8601 UTC timestamps.
14. **Do not**: return rendered bodies, full addresses, internal stack traces, DB ids of other tenants; expose dev endpoints outside `local`/`test`.
