# OWASP API Security Top 10 (2023) Analysis

Self-assessment of the reference implementation against the 2023 list. "Implemented" means designed in CONTRACT and implemented in source by the backend agent (verify in code; status of tests in `docs/validation/VALIDATION_REPORT.md`). This is not a certification or pen-test.

| ID | Risk | Exposure here | Controls | Gaps / notes |
|---|---|---|---|---|
| API1:2023 | Broken Object Level Authorization | Message, template, job, recipient ids in paths | Tenant from JWT; tenant-scoped repositories; 404 for foreign; UUID ids (non-guessable but not relied upon) | RLS in PostgreSQL not used (portable SQL): defense in depth missing |
| API2:2023 | Broken Authentication | Bearer JWT; dev token endpoint; callbacks via HMAC | RS256 validation (issuer-uri in prod); `/dev/token` only `local`/`test`; short-lived tokens; HMAC for callbacks | No token revocation, no refresh handling (IdP responsibility); no brute-force controls (no passwords here) |
| API3:2023 | Broken Object Property Level Authorization | DTO exposure and mass assignment | Response DTOs never include rendered body, full address; masked addresses; request DTOs whitelist fields; `status`/`tenantId` not writable | Field-level role differences (e.g., auditor vs operator) limited |
| API4:2023 | Unrestricted Resource Consumption | Submit floods, bulk CSV, page size, provider spend | 256 KB body limit, rate limit 50 rps / burst 100, page size <= 200, bulk max rows and rate, queue backpressure 503, bounded executors, timeouts | No per-tenant monthly quota/cost cap (listed in prod gap) |
| API5:2023 | Broken Function Level Authorization | Ops, provider config, fault mode, replay | Role matrix in CONTRACT 5; admin endpoints `PLATFORM_ADMIN`; fault-mode local/test only | Role matrix tested per endpoint in `tests/security` |
| API6:2023 | Unrestricted Access to Sensitive Business Flows | Bulk marketing sends, OTP/security alert abuse | Bulk needs scope; consent policy; per-recipient frequency (see source); idempotency; audit | No CAPTCHA/anti-automation (not user-facing); anomaly detection absent |
| API7:2023 | Server Side Request Forgery | Provider URLs | No caller-supplied URLs fetched; provider endpoints from config | Egress allow-list is a deployment control (not executed) |
| API8:2023 | Security Misconfiguration | Actuator, CORS, error detail, headers, profiles | Minimal actuator exposure; `ApiError` without stack traces; CORS restricted to console origin (see source); security headers (see source); profile separation; no default creds | TLS terminates at ingress (not configured here); verify headers in `tests/security` |
| API9:2023 | Improper Inventory Management | Versions, debug endpoints | Single `/api/v1`; OpenAPI committed and asserted against springdoc; dev endpoints profile-gated; `API-Version` header | Old version retirement process described in `docs/leadership/api-contract-guide.md` |
| API10:2023 | Unsafe Consumption of APIs | Provider responses and callbacks treated as untrusted | Callback HMAC + schema mapping; response size/time limits; outcome enum mapping (unknown => AMBIGUOUS, not success); provider data never executed or rendered unescaped | Real vendors' TLS pinning, IP allow-lists not modeled |

## Other relevant practices
- Input validation: Bean Validation on DTOs, max lengths, enum parsing returns `MALFORMED_REQUEST`.
- Error handling: consistent `ApiError`, no internal details, `correlationId` for support.
- Logging: structured JSON, MDC `correlationId`, `tenant`, `messageId`; PII redaction (`common.Redaction`).
- Security testing: `tests/security` scripts, unit tests; SAST/DAST/dependency scanning pipeline stages are configured statically, not executed here.
