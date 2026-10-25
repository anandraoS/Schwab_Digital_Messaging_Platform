# Security Review Checklist

Map: `docs/security/threat-model.md` (T1-T18), `owasp-api-top10-2023-analysis.md`.

## Authentication
- [ ] Endpoint requires JWT unless explicitly callback/health/dev; dev endpoints gated to `local`/`test` with a test proving 404 in `prod-like`.
- [ ] Callback endpoint verifies HMAC on raw bytes, constant-time compare, timestamp tolerance, key id lookup, replay guard; unsigned requests never reach processors.
## Authorization
- [ ] Role rule in `SecurityConfig` and/or method security; role matrix test updated.
- [ ] Tenant from JWT only; repositories tenant-scoped; foreign resource => 404; admin scoping via header only for `tenant=*`.
- [ ] Four-eyes (approver != author) preserved; bulk requires SENDER + `messages:bulk`.
## Input and output
- [ ] Bean Validation, size limits, enum strictness; no mass assignment; no SSRF (no caller-supplied URL fetch).
- [ ] Template changes: allow-list preserved; HTML escaping; no expression evaluation.
- [ ] Responses masked; no rendered body; errors without internals.
## Secrets and data
- [ ] No secrets/tokens/keys in code, config, tests, docs, logs; dummy values labelled.
- [ ] Logs/diagnostics redacted; bus envelope ids only; Mongo payloads redacted + TTL.
- [ ] Retention for new data defined.
## Abuse and availability
- [ ] Rate limit, body limit, pagination cap, bulk caps apply to new endpoint; bounded resources.
## Supply chain and config
- [ ] New dependencies justified, from BOM where possible, scanned (static config; N/E here).
- [ ] Actuator exposure unchanged; CORS unchanged or reviewed.
## Process
- [ ] Sensitive change got independent review (not the author, preferably a different agent/person).
- [ ] Threat model row added/updated; tests named in the row exist.
