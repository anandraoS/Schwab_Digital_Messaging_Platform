# ADR-0011: Multi-tenancy and authorization model

Status: Accepted (2026-10-18)

## Context
Several business units (synthetic tenants `tenant-retail`, `tenant-wealth`) share one deployment. One tenant must never read, mutate, enumerate or infer another tenant's messages, templates, recipients or jobs. Humans (operators, approvers) and service clients (senders) have different powers.

## Decision
- **Shared database, shared schema, `tenant_id` discriminator** on every tenant-owned table; tenant is derived from the verified JWT claim `tenant` (never from a request body). `*` is allowed only with `ROLE_PLATFORM_ADMIN`, who may scope with `X-Tenant-Id`.
- `security.TenantContext` (request-scoped) is populated by the security layer; repositories take tenant as an explicit parameter (`findByIdAndTenantId`). There is no "find by id" for tenant-owned entities in the repository layer used by the web layer.
- **404, not 403**, for resources owned by another tenant (no existence oracle). 403 is reserved for role failures within the caller's own tenant.
- Authorization layers:
  1. URL/method rules in `SecurityConfig` (coarse role check).
  2. Method-level checks and `AuthorizationService` (resource-level: tenant ownership, template four-eyes approver != author, bulk requires `ROLE_SENDER` + scope `messages:bulk`).
  3. Frontend guards are UX only and never trusted.
- Roles: `PLATFORM_ADMIN, TENANT_ADMIN, TEMPLATE_AUTHOR, TEMPLATE_APPROVER, OPERATOR, SENDER, AUDITOR`. The authoritative role-by-endpoint matrix is CONTRACT section 5.
- Callbacks are tenant-less at the network layer: tenant is resolved from the message found by `providerMessageId`/`idempotencyRef`, after signature verification.
- Audit: every privileged action writes `audit_event` with `actor`, `actor_role`, `tenant_id`, `correlation_id`.
- Rate limits are per client (`sub`) with tenant-level ceilings.

## Alternatives considered
| Option | Why not (for reference scale) |
|---|---|
| Schema-per-tenant | Migration fan-out, pool explosion, but stronger isolation; valid evolution for regulated tenants |
| Database-per-tenant | Strongest isolation, highest cost |
| Row-level security (PostgreSQL RLS) | Not portable to H2/Oracle under the portable-SQL rule; recommended as defense-in-depth on PostgreSQL (see `docs/security/prod-vs-reference.md`) |
| Tenant from header/body | Spoofable |
| 403 for foreign tenant | Leaks existence (BOLA recon) |

## Consequences
- Positive: simple ops, one schema, enumeration-resistant.
- Negative: a missing `tenant_id` predicate is a data-leak bug; mitigated by repository signature convention, cross-tenant tests for every endpoint, and the review checklist (`ai-engineering/ai-code-review/06-incorrect-authorization-cross-tenant.md`).
- Noisy neighbour: per-client and per-tenant rate limits, bulk job caps, provider token buckets.

## Validation
- `tests/security` cross-tenant matrix (every GET/PUT/POST by id with tenant B token against tenant A resource expects 404); controller slice tests with `@WithMockUser`-style principals (see source `security` and `web` tests).
- OWASP mapping: API1 BOLA, API5 BFLA in `docs/security/owasp-api-top10-2023-analysis.md`.
