# Test Generation Checklist (human or AI)

Before generating tests, read `context/testing.md` and the code under test.

## Choose level
Pure logic: unit. Controller contract/security: `@WebMvcTest`/integration. Cross-component or distributed failure: `AbstractIntegrationTest`. Real infra behavior: `*ContainerIT` (cannot be executed without Docker; mark accordingly).

## Content rules
- [ ] Arrange with builders/synthetic data (`Recipient 001`, `example.test`), never real data or secrets.
- [ ] Inject `Clock`/`RandomGenerator`; advance fake time instead of sleeping.
- [ ] One behavior per test; name states behavior: `methodOrScenario_condition_expectedResult`.
- [ ] Assert outcomes: DB state, status, attempt rows, audit, metrics counters, provider call counts. Not just "no exception".
- [ ] Negative paths: invalid, unauthorized, cross-tenant, duplicate, out-of-order, expired, boundary values.
- [ ] Concurrency: latch start gate, >= 20 threads, assert invariants.
- [ ] Table-driven for matrices (state machine, policy, outcome mapping, roles).
- [ ] Failure injection via `FaultInjector` and wrappers; verify recovery state.
- [ ] Not tautological: the test must fail if the production code is mutated (check: break the code, see test fail).
- [ ] No test depends on execution order or shared mutable state; unique H2 db per context.
- [ ] No `@Disabled`, no commented assertions.
## Required coverage per feature
Happy path, validation, authz (401/403/404), idempotency, failure/retry, observability counter, audit record.
## After generating
Run the tests (record exact command and result in the PR); if not run, say so. Review generated tests as production code.
