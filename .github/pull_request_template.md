## Summary
<!-- What and why. Link the spec/issue. -->

## Type
- [ ] Feature  - [ ] Bug fix  - [ ] Refactor  - [ ] Docs  - [ ] Infra  - [ ] Security-sensitive (requires independent review)

## Changes
<!-- Bullet list; files/packages touched. -->

## Testing (be exact and honest)
| Command | Result | Executed? |
|---|---|---|
| e.g. `mvn -B verify` | pass/fail | yes/no |

Not executed (and why): <!-- Docker, k6, Testcontainers, Helm, CF, etc. -->

## Checklist (`docs/leadership/pr-checklist.md`)
- [ ] State changes via `MessageStateMachine` + CAS; transition tests updated
- [ ] Idempotency considered for each new write path
- [ ] Tenant-scoped data access; cross-tenant test added; foreign resource returns 404
- [ ] Role/authorization rules and matrix tests updated
- [ ] No transaction across remote calls; timeouts set; pools/queues bounded
- [ ] No secrets, bodies, full addresses in code/logs/responses/bus
- [ ] Migrations expand-only and portable; Oracle DDL updated; indexes justified; N+1 checked
- [ ] API change is backward compatible; OpenAPI updated
- [ ] Metrics use CONTRACT names; alerts/runbooks updated for new failure modes
- [ ] Docs/ADR/spec updated; no ADR contradicted without a superseding ADR
- [ ] Failure-path tests included; tests assert behavior (not tautological)

## AI assistance
- [ ] AI-assisted: tool(s) and what for: <!-- e.g., Claude Code: drafted tests -->
- [ ] I reviewed all generated code and tests, and verified invariants in `ai-engineering/context-engineering.md`
- [ ] Sensitive change: independent reviewer assigned (not the author)

## Risks and rollback
<!-- Impact, feature flag, rollback steps. -->
