# Definition of Done

A change is done when all hold (N/A with reason allowed):
1. Acceptance criteria from the spec are met and traceable to tests (`docs/leadership/acceptance-criteria.md` ids).
2. Code reviewed (`code-review.md`); sensitive changes independently reviewed (`security-review.md`).
3. Unit + integration tests added and passing locally; command and result recorded in the PR. Failure-path tests included.
4. `mvn -B verify` (backend) / `ng test --watch=false` + production build (frontend) green, or the PR states what was not run.
5. No new warnings in build; no TODO/FIXME without ticket.
6. DB migrations expand-only, portable, indexed, Oracle DDL updated.
7. API changes backward compatible; OpenAPI updated; contract test passes.
8. Metrics, logs, traces added per `context/observability.md`; alert and runbook updated for new failure modes.
9. Security: threat model row updated; no secrets/PII leakage; authz and tenant tests.
10. Docs updated (ADR/spec/architecture/runbook/README); honest "not executed" labels.
11. Config keys documented with safe defaults; profiles verified (`local`, `test`, `prod-like`).
12. Rollback path understood (`docs/operations/rollback-plan.md`); feature flag where risky.
13. Handoff note written if work continues with another agent/person (`templates/handoff-note-template.md`).
