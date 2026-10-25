---
mode: agent
description: Independent security review of the current diff against this project's invariants and threat model
---
Act as an independent security reviewer (you did not write this change). Review the diff of `${input:target:the current branch vs main}`.

Load: `ai-engineering/context/security.md`, `ai-engineering/checklists/security-review.md`, `docs/security/threat-model.md`.

For each changed file determine which of T1-T18 in the threat model it touches. Then check:
- tenant derived from verified JWT only; every repository access tenant-scoped; foreign resource returns 404
- role rules and the endpoint's presence in the role/tenant matrix tests
- callback HMAC on raw bytes, constant-time compare, tolerance, replay guard; no bypass
- template allow-list, escaping, four-eyes
- no secrets, bodies, full addresses, tokens or signatures in logs, responses, bus, diagnostics
- input bounds, no mass assignment, no SSRF, dev-only endpoints gated by profile
- new dependencies and config exposure (actuator, CORS)

Output a table: severity, file:line, issue, exploit sketch, fix suggestion, failing test idea. End with a verdict: APPROVE or CHANGES REQUESTED. Do not modify files. Do not claim to have run anything you did not run.
