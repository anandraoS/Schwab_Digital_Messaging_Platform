# Threat Model (STRIDE-style)

Scope: the messaging platform API, dispatch pipeline, callbacks, console, data stores. Synthetic data; reference implementation. Test locations: `tests/security` (HTTP-level scripts) and `backend/src/test/java/.../security|callback|web` (see source for exact class names). STRIDE letters: S spoofing, T tampering, R repudiation, I information disclosure, D denial of service, E elevation of privilege.

## Assets and trust boundaries
Assets: message content (variables, rendered text in flight), recipient addresses and consent, provider credentials and HMAC secrets, audit trail, template content, availability of critical alerts. Boundaries: internet to edge; edge to app; app to data stores; app to providers; providers to callback endpoint.

## Threat table
| # | Threat | STRIDE | Attack example | Mitigation | Code location | Test |
|---|---|---|---|---|---|---|
| T1 | Unauthorized submission | S, E | Call `POST /messages` without token, expired token, or with `ROLE_AUDITOR` | Resource-server JWT (RS256, issuer/audience/exp validated); URL role rules; method security | `config.SecurityConfig`, `web.MessageController` | `tests/security`: no token 401; wrong role 403; expired 401 |
| T2 | Cross-tenant access (BOLA) | I, T | Tenant B requests tenant A message id, template, job, recipient | Tenant from JWT claim only; `findByIdAndTenantId`; 404 not 403; admin scoping via `X-Tenant-Id` only for `tenant=*` | `security.TenantContext`, `security.AuthorizationService`, `repository` | cross-tenant matrix on every id-based endpoint expects 404 |
| T3 | Forged callbacks | S, T | Attacker POSTs fake DELIVERED to `/callbacks/{providerId}` | HMAC-SHA256 over `t.rawBody`, constant-time compare, key by `X-Provider-Key-Id`, 5 min tolerance | `callback.SignatureVerifier` | `SignatureVerifierTest`; `tests/security` bad/missing/expired signature expects 401 + `messaging.callbacks{result="invalid_signature"}` |
| T4 | Callback and request replay | S, T | Capture a valid signed callback and resend | `X-Event-Id` replay cache, unique `(provider_id, provider_event_id)`, timestamp window | `callback.ReplayGuard`, `callback.DeliveryEventProcessor` | `ReplayGuardTest`; `FailureRecoveryIT#scenario03_...` |
| T5 | Credential exposure | I | Secrets in git, logs, images, error messages | No secrets in files (dummy dev secrets only, labelled), env/secret store, `Redaction`, log scrubbing, secret scanning in CI (config only, not executed here) | `common.Redaction`, `config.PropertiesConfig`, `docs/security/secrets-management.md` | `RedactionTest`; repo grep rule in review checklist |
| T6 | Content leakage | I | Rendered body or address in logs, API, Mongo, bus | Envelope has ids only; variables stored not body; API returns masked addresses; Redaction in diagnostics; no body at INFO | `messaging.DispatchEnvelope`, `web` DTOs, `diagnostics` | contract test: `MessageDetail` JSON has no `body`/full address; log capture test for PII patterns |
| T7 | Template injection | T, E, I | `{{` expressions, HTML/JS in variables, SpEL/OGNL gadget | Allow-listed `{{var}}` substitution, no evaluation, HTML escape for email HTML, variable allow-list and length limits, four-eyes approval | `template.TemplateRenderer`, `template.VariableValidator`, `service.TemplateService` | `TemplateRendererTest` (script tags, nested braces, unknown vars, 10k-char value) |
| T8 | Duplicates (accidental or malicious) | T, D | Replaying POST to spam a recipient | Idempotency key, rate limits, per-recipient frequency policy (config, see source) | `service.IdempotencyService`, `security.RateLimitFilter` | `FailureRecoveryIT#scenario02_...` |
| T9 | Abuse / DoS | D | Flood API, huge bodies, giant bulk CSV, slow loris, provider exhaustion | 256 KB body limit (413), per-client token bucket 429, queue-depth backpressure 503, bulk `max-rows`, bounded executors, provider rate limiter, timeouts | `security.RequestSizeFilter`, `security.RateLimitFilter`, `messaging`, `batch` | `RateLimitFilterTest`, `RequestSizeFilterTest`, `FailureRecoveryIT#scenario10_...` |
| T10 | Privilege escalation | E | SENDER calls `/ops/consumers/pause`, edits provider config, approves own template, sets `tenant` claim | Role matrix, four-eyes (approver != author), fault-mode endpoint disabled outside local/test, dev token disabled outside local/test, JWT signature verification | `SecurityConfig`, `AuthorizationService`, `security.DevTokenController` (profile-gated) | `tests/security`: role matrix; `DevTokenControllerProfileTest` (prod-like profile returns 404) |
| T11 | Unauthorized bulk sends | E, T | Low-privilege caller uploads CSV to blast recipients | `ROLE_SENDER` + scope `messages:bulk`, tenant scoping of recipients, max rows, rate cap, consent policy re-applied at dispatch, audit with `created_by` | `batch.BulkJobController`, `service.PolicyService` | `BulkJobSecurityTest` (missing scope 403), policy tests for opted-out rows |
| T12 | Repudiation | R | Operator replays/discards DLQ then denies | Append-only `audit_event` with actor, role, correlation id; no update/delete in app; DB privileges in prod | `service.AuditService` | audit assertion in replay/discard/cancel tests |
| T13 | Tampering with state | T | Operator forces illegal transition | State machine + 409; ops actions limited to allowed edges | `domain.MessageStateMachine` | `MessageStateMachineTest` |
| T14 | Log injection / header injection | T | Newline in `X-Correlation-Id` | Correlation id validated (charset, length) else regenerated | `common.CorrelationIdFilter` | `CorrelationIdFilterTest` |
| T15 | SSRF | I | Callback or channelOptions URL causes server fetch | No server-side fetch of caller-supplied URLs; provider endpoints from config only | `provider` | code review rule |
| T16 | Mass assignment | T, E | Client sets `status`, `tenantId` in request body | DTO records with explicit fields; unknown properties rejected | `web` DTOs | `MALFORMED_REQUEST` test |
| T17 | Dependency/supply chain | T | Vulnerable library | Spring Boot BOM pinned, dependency scan in CI (config only, not executed) | `pom.xml`, `.github/workflows` | not executed |
| T18 | Sensitive actuator exposure | I | Public `/actuator/prometheus`, heap dump | Only health + prometheus exposed, prometheus network-restricted in prod | `application*.yml`, network policy | static check |

## Residual risks
Documented in `prod-vs-reference.md`: no WAF, no real KMS, no mTLS enforced by default, JWT revocation not implemented, no automated pen-test, no DLP.

## Review cadence
Update on every new endpoint (checklist `ai-engineering/checklists/security-review.md`). Sensitive changes need independent review (multi-agent workflow).
