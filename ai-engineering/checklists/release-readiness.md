# Release Readiness (AI workspace pointer)

Authoritative list: `docs/operations/release-readiness-checklist.md`. Summary gates for AI-assisted releases:

1. All DoD items satisfied (`definition-of-done.md`).
2. `FailureRecoveryIT` (10 scenarios), security matrix, state machine matrix green; results from executed runs only.
3. Independent review recorded for any change touching authz/tenancy, callbacks, template engine, state machine, retry/idempotency, consent policy.
4. Invariants list (`../context-engineering.md`) re-verified by a reviewer who did not write the change.
5. ADR compliance check: no ADR contradicted without a superseding ADR.
6. Deploy assets validated statically; unexecuted items listed (Docker, K8s, Helm, CF, k6, Testcontainers).
7. Runbooks and alerts updated; rollback plan confirmed; canary criteria defined.
8. Validation report (`docs/validation/VALIDATION_REPORT.md`) regenerated from real outputs.
