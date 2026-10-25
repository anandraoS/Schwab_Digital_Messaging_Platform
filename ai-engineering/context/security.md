# Context: Security
Load when: auth, tenancy, callbacks, templates, logging, secrets, new endpoints. Authority: ADR-0011/0012, `docs/security/*`.

- Roles: PLATFORM_ADMIN, TENANT_ADMIN, TEMPLATE_AUTHOR, TEMPLATE_APPROVER, OPERATOR, SENDER, AUDITOR. Bulk: SENDER + scope `messages:bulk`.
- Tenant comes only from the verified JWT (`TenantContext`); repositories take tenant param; foreign => 404.
- Frontend guards are UX only.
- Callbacks: `permitAll` in chain, HMAC-SHA256 over `t + "." + rawBody` in `SignatureVerifier`; headers `X-Signature: t=<epoch>,v1=<hex>`, `X-Provider-Key-Id`, `X-Event-Id`; tolerance 5 min; constant-time compare; replay cache. Never disable or loosen to "make it work".
- Template safety: allow-listed `{{var}}`, no expression evaluation, HTML escape for email HTML, four-eyes approval (approver != author).
- Data: no body at rest; masked addresses in API; ids only on the bus; `Redaction` in logs/diagnostics.
- Secrets: never in repo; dummy `local-dev-secret-not-for-production` only for local/test; env/secret store otherwise; fail fast in prod-like if missing.
- Dev-only: `/dev/token`, `/dev/simulator/**`, fault-mode: enabled only in `local`/`test`.
- Consent: ordered policy (suppression > category rules > quiet hours); fallback never bypasses consent; re-evaluated at dispatch and replay.
- Abuse limits: body 256 KB, 50 rps/burst 100, page <= 200, bulk max rows, queue backpressure 503.
- Security-sensitive changes require independent review (multi-agent workflow).
