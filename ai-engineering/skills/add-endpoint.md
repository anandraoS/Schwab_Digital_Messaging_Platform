# Skill: Add a REST Endpoint

**When**: new or changed API operation. **Load**: `context/api.md`, `context/security.md`, `standards/api-design.md`.

## Inputs
Operation purpose, roles, request/response shapes, tenant ownership of the resource.

## Steps
1. Write/extend the OpenAPI entry first (`messaging-api-v1.yaml`): path, roles in description, request/response records, error responses (codes from the list).
2. DTO records with Bean Validation; response DTO masks addresses and excludes bodies.
3. Controller in `web`: thin; obtain tenant from `TenantContext`; call service.
4. Service method: tenant-scoped repository access (`findByIdAndTenantId`), state machine/CAS for status changes, audit event, metrics.
5. Security: add URL rule + method security; resource-level check in `AuthorizationService` if needed.
6. Idempotency/concurrency: define behavior on repeat; optimistic lock if update.
7. Tests: happy; validation 400; 401; 403 wrong role; foreign-tenant 404; conflict 409 if relevant; OpenAPI contract test passes; statement count for lists.
8. Docs: `docs/leadership/api-contract-guide.md` if conventions touched; CONTRACT/DEVIATIONS if path/role differs; copy spec to `docs/api/openapi.yaml` (lead handles export).

## Output
PR with spec, code, tests; checklist `code-review.md` ticked.

## Guardrails
No entity in responses; no tenant from body; no new error codes without updating the list; additive only in v1.
