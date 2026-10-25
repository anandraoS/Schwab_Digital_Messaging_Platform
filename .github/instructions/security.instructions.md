---
applyTo: "backend/src/main/java/**/{security,config,callback,template,web}/**/*.java"
---
# Security-sensitive code rules

Context: `ai-engineering/context/security.md`; checklist `ai-engineering/checklists/security-review.md`; threat model `docs/security/threat-model.md`. Changes here require independent review (`ai-engineering/multi-agent-workflow.md`).

- Tenant from verified JWT claim only; never from body/query/header (except `X-Tenant-Id` for `tenant=*` platform admins). Every id-based read/write is tenant-scoped; foreign resource returns 404.
- Add endpoint roles in `SecurityConfig` and add the endpoint to the role/tenant matrix tests.
- Callbacks: verify HMAC-SHA256 over `t + "." + rawBody` using raw bytes, constant-time comparison, 5 minute tolerance, key by `X-Provider-Key-Id`, replay guard by `X-Event-Id`. Never disable, bypass or loosen verification, even for debugging.
- Templates: allow-listed `{{var}}` substitution only; no expression evaluation, reflection, includes; HTML escape for email HTML; approver differs from author.
- Dev-only features (`/dev/**`, fault mode) only in `local`/`test` profiles.
- No secrets in code/config/tests/logs; read from environment/secret store; dummy `local-dev-secret-not-for-production` allowed only for local/test.
- Responses and logs: masked addresses, no rendered bodies, no tokens/signatures; use `Redaction`.
- Validate and bound all input (size, length, enum strictness); no mass assignment; no server-side fetch of caller-supplied URLs.
- Prefer secure defaults. If security conflicts with convenience, choose security and explain the trade-off in the PR.
