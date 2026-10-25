# 16. Security and Audit Architecture

## 16.1 Security principles

1. **Never trust the network.** Every protected call carries a JWT; callbacks are authenticated by HMAC even though the HTTP layer lets them through.
2. **Tenant isolation by construction.** The tenant comes from the token, never from the request body, and every repository query is tenant scoped.
3. **Least privilege.** Seven roles and one scope; endpoint access is a table, not an if-statement scattered through code.
4. **Minimise data.** No content or personal data on the bus, rendered text not stored, addresses masked in every response, redaction in logs, audit and Mongo.
5. **Secure by default.** No default Spring profile, so a forgotten profile cannot enable the dev token issuer; actuator, OpenAPI docs and Prometheus are restricted unless a local profile opens them.

{{diagram:securityLayers}}

## 16.2 Authentication

| Surface | Mechanism | Implementation |
|---|---|---|
| API | Spring Security 6 OAuth2 resource server, **JWT RS256**. Claims: `sub`, `roles` (array), `tenant` (string; `*` only for platform admin), `scope` | `config/SecurityConfig`, `config/JwtConfig`, `security/JwtAuthConverter` |
| Production identity | `spring.security.oauth2.resourceserver.jwt.issuer-uri` from the environment | `application-prod-like.yml` |
| Local identity | `POST /dev/token` mints RS256 JWTs from an in-memory generated key; validated with the matching public key (`NimbusJwtDecoder.withPublicKey`); enabled only when `messaging.security.dev-token-enabled=true` (profiles `local`, `test`) | `security/DevTokenController`; `DevTokenDisabledIT` proves it is off otherwise |
| Provider callbacks | HMAC-SHA256 (Chapter 12) | `callback/SignatureVerifier` |
| Identity provider unreachable | An infrastructure failure (for example an unreachable JWKS endpoint) becomes 503 `SERVICE_UNAVAILABLE` with `Retry-After`; the request is still denied, never treated as authenticated | `security/IdentityProviderUnavailableFilter` |

The Spring Security rules, in order (`SecurityConfig`): `OPTIONS` denied; `/api/v1/callbacks/**` and `/actuator/health/**` open; `/actuator/prometheus` restricted to `PLATFORM_ADMIN` and `OPERATOR` unless `metrics-public`; every other `/actuator/**` for `PLATFORM_ADMIN`; `/dev/token` open (and only exists locally); OpenAPI docs open only when `docs-public`; `/api/**` authenticated.

## 16.3 Authorization

### Role matrix

| Capability | SENDER | TENANT_ADMIN | PLATFORM_ADMIN | OPERATOR | TEMPLATE_AUTHOR | TEMPLATE_APPROVER | AUDITOR |
|---|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| Submit message, create bulk job (needs scope `messages:bulk`) | yes | yes | yes | | | | |
| Read messages, status, history | yes | yes | yes | yes | yes | yes | yes |
| Cancel message | yes | yes | yes | yes | | | |
| Requeue, replay or discard dead letter | | | yes | yes | | | |
| View dead letters, failed messages | | yes | yes | yes | | | |
| Delivery events | | yes | yes | yes | | | yes |
| Audit events | | yes | yes | | | | yes |
| Create and edit template drafts, submit | | yes (create) | | | yes | | |
| Approve, reject template | | | | | | yes | |
| Retire template | | yes | | | | yes | |
| Preview template | | yes | | yes | yes | | |
| Edit preferences, suppressions | | yes | yes | yes | | | |
| Provider config, pause consumers | | | yes | | | | |
| Queue stats | | | yes | yes | | | |

The table mirrors the endpoint role list of the contract and the `ACCESS` constants of the Angular console; `SecurityMatrixTest` and `SecurityIT` exercise it, and the console's guards are UX only.

### Tenant isolation

`TenantContext` holds the tenant claim for the request. Non-admin principals see only their tenant's data; a resource owned by another tenant answers **404, not 403**, so its existence is not disclosed. `PLATFORM_ADMIN` carries `tenant=*` and may pass `X-Tenant-Id` to scope a call. `TenantIsolationIT` proves the 404 behaviour for GET, cancel, history and status; ADR-0011 records the shared-schema decision and lists row-level security as the production hardening.

### Object-level checks

`AuthorizationService` performs resource-level checks beyond roles: the tenant owns the template, the approver differs from the author, and the bulk scope is present. A refused bulk action is audited (`BULK_JOB_DENIED`).

## 16.4 Data protection

| Control | Mechanism |
|---|---|
| Masked responses | `MessageDetail` carries `maskedAddress` only (`r***@example.test`, `+1555***0101`); `common/Redaction.maskEmail`, `maskPhone`, `maskToken` |
| No content at rest | Variables are stored; the rendered body is not (ADR-0007) |
| No content on the bus | `DispatchEnvelope` holds identifiers only |
| Log redaction | `Redaction` masks emails, long numbers and bearer tokens in free text; structured logs carry `correlationId`, `tenant`, `messageId` only; `LoggingPrivacyIT` asserts it |
| Mongo diagnostics | Redacted payloads only; write failures never fail processing (`messaging.diagnostics.write.failures`) |
| Free-text vendor reasons | Never copied into audit or log lines; only code-like reasons |
| Suppression storage | Address hash, not the address |
| Request limits | 256 KB body, rate limit, page size at most 200, bulk row and file limits |
| Secrets | Never in files: environment placeholders, `cf set-env` or user-provided services, Kubernetes Secret templates with dummy values; dummy callback secrets are `local-dev-secret-not-for-production` |

## 16.5 Audit architecture

The audit trail is **append-only** and written in the same transaction as the change it records (`service/AuditService`, table `audit_event`: `actor`, `actor_role`, `action`, `entity_type`, `entity_id`, `correlation_id`, `detail_json`, `occurred_at`). Because it shares the transaction, there is no state change without an audit row and no audit row for a change that rolled back.

| Group | Actions (selection from `domain/AuditAction`) |
|---|---|
| Message | `MESSAGE_ACCEPTED`, `MESSAGE_REJECTED`, `MESSAGE_TRANSITION`, `MESSAGE_CANCELLED`, `MESSAGE_REQUEUED`, `MESSAGE_DISCARDED`, `MESSAGE_REPLAYED`, `MESSAGE_FALLBACK`, `REPLAY_PRECHECK` |
| Delivery | `DELIVERY_EVENT_APPLIED`, `DELIVERY_EVENT_CONFLICT` |
| Template | `TEMPLATE_CREATED`, `TEMPLATE_VERSION_CREATED`, `TEMPLATE_VERSION_EDITED`, `TEMPLATE_SUBMITTED`, `TEMPLATE_APPROVED`, `TEMPLATE_REJECTED`, `TEMPLATE_RETIRED` |
| Preferences | `PREFERENCE_UPDATED`, `SUPPRESSION_ADDED`, `SUPPRESSION_REMOVED` |
| Operations | `PROVIDER_CONFIG_UPDATED`, `CONSUMERS_PAUSED`, `CONSUMERS_RESUMED`, `STUCK_SWEEP`, `RECONCILIATION_CHECK` |
| Bulk and retention | `BULK_JOB_CREATED`, `BULK_JOB_STOPPED`, `BULK_JOB_RESTARTED`, `BULK_JOB_DENIED`, `BULK_JOB_ABANDONED`, `RETENTION_PURGE` |

Reads: `GET /api/v1/audit-events` (AUDITOR, TENANT_ADMIN, PLATFORM_ADMIN; filters `entityType`, `entityId`, `action`, `actor`, `from`, `to`) and the merged per-message timeline `GET /api/v1/messages/{id}/history`, which interleaves audit events, attempts and delivery events in time order. Retention keeps audit 365 days by default, with legal hold support (Chapter 15).

## 16.6 Threats and where they are addressed

| Threat | Control | Test or evidence |
|---|---|---|
| Forged callback | HMAC with 5-minute tolerance, constant-time compare | `SignatureVerifierTest`, `CallbackApiIT`, AC-12 |
| Replayed callback | Replay guard, unique event key | `ReplayGuardTest`, scenario 3 |
| Cross-tenant access (BOLA) | Tenant scoped queries, 404 policy | `TenantIsolationIT` |
| Template injection | Allow-listed grammar, no expressions | `TemplateRendererTest` |
| Header injection in email | CR and LF stripped, custom header regex | `TemplateRendererTest`, validation in `NotificationService` |
| PII in logs | Redaction, no content stored | `LoggingPrivacyIT`, `RedactionTest` |
| Abuse and resource exhaustion | Rate limit, body size, bounded pools, backpressure | `RateLimitIT`, `BackpressureIT` |
| Token forgery | RS256 signature validation, issuer in production | `JwtAuthConverterTest`, `SecurityIT` |
| Dev endpoints in production | No default profile, flag, `DevTokenDisabledIT` | `DevTokenDisabledIT` |
| Privilege escalation through template edit | `created_by` overwritten by editor, four eyes | `TemplateLifecycleIT` |

## 16.7 Honest boundary

This is a reference implementation. `docs/security/prod-vs-reference.md` lists what a production deployment still needs (row-level security, a real identity provider, WAF, secret manager, mutual TLS, formal pen testing, compliance review). CI definitions for OWASP dependency-check, npm audit and Trivy exist but were **not executed**.

## Reference material

{{embed:docs/security/threat-model.md|title=Threat model}}

{{embed:docs/security/owasp-api-top10-2023-analysis.md|title=OWASP API Top 10 (2023) analysis}}

{{embed:docs/security/service-to-service-auth.md|title=Service-to-service authentication}}

{{embed:docs/security/secrets-management.md|title=Secrets management}}

{{embed:docs/security/data-minimization-and-retention.md|title=Data minimisation and retention}}

{{embed:docs/security/prod-vs-reference.md|title=Production versus reference}}

{{embed:docs/adr/0011-multi-tenancy-and-authorization.md|title=ADR-0011: Multi-tenancy and authorization}}
