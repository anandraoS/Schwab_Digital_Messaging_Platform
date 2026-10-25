---
applyTo: "**/src/test/**/*.java,**/*.spec.ts,tests/**"
---
# Test rules

Full guidance: `ai-engineering/context/testing.md`, `ai-engineering/checklists/test-generation.md`.

- Assert outcomes (DB state, status, attempts, audit, metrics, provider call counts), not just "no exception" or `verify(...)`.
- Deterministic: fixed/advanceable `Clock`, seeded random, no `Thread.sleep` (await with timeout), unique H2 database per context.
- Integration tests extend `AbstractIntegrationTest` (profile `test`). Testcontainers tests are `*ContainerIT` with `@Testcontainers(disabledWithoutDocker = true)`.
- Failure scenarios live in `FailureRecoveryIT` with the exact method names in `docs/architecture/reliability-scenarios.md`.
- Race fixes need a concurrent test (latch gate, at least 20 threads) asserting the invariant.
- Cover negative paths: invalid input, 401/403, cross-tenant 404, duplicates, out-of-order events, expiry, boundaries.
- Table-driven tests for matrices: state transitions, policy, roles, provider outcomes.
- A test must fail when the guarded behavior is removed; do not weaken assertions to make tests pass.
- Synthetic data only; no secrets; never assert on log text for behavior.
- Do not claim a test passed unless it was run; report command and result.
