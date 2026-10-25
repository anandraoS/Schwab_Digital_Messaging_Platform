# Test Strategy

## Principles
1. Test behavior at the lowest level that can prove it; prove distributed-failure behavior at integration level with deterministic fault injection.
2. Deterministic: injected `Clock` and `RandomGenerator`; no sleeps (use awaiting helpers with timeouts); unique H2 db per context.
3. Never claim a result without executing: results live only in `docs/validation/VALIDATION_REPORT.md`.

## Pyramid
| Layer | Scope | Tools | Examples |
|---|---|---|---|
| Unit | pure logic | JUnit 5, AssertJ, Mockito sparingly | `MessageStateMachineTest`, `RetryPolicyTest`, `TemplateRendererTest`, `SignatureVerifierTest`, `PolicyServiceTest` |
| Slice | web/security/repository | `@WebMvcTest`, `@DataJpaTest` | role matrix, validation, ApiError shape |
| Integration | full Spring context, H2, in-memory bus | `AbstractIntegrationTest` | `NotificationFlowIT`, `FailureRecoveryIT`, `CallbackIT`, `BulkJobIT` |
| Container | real PG/Rabbit/Redis/Mongo | Testcontainers, `-Pcontainers`, `disabledWithoutDocker=true` | `*ContainerIT` (N/E here) |
| Contract | OpenAPI vs implementation | springdoc path assertion test | `OpenApiContractTest` |
| Frontend unit | services, interceptors, guards, components | Karma + Jasmine | per CONTRACT 10 |
| API e2e | HTTP against running app | node `fetch` scripts in `tests/end-to-end`, `.http` files in `tests/api-http` | happy path, idempotency, callbacks |
| Security | authz matrix, forged callbacks | `tests/security` scripts | cross-tenant 404 |
| Failure recovery | scripted faults | `tests/failure-recovery` + `FailureRecoveryIT` | 10 scenarios |
| Performance | load | k6 (N/E), JVM harness (executable) | `LoadHarnessIT` |

## Must-have test matrices
- State machine: all (from,to) pairs vs table.
- Policy: category x channel x consent x suppression x quiet hours x priority.
- Authorization: endpoints x roles x tenant (own/foreign).
- Provider outcomes: each `ProviderOutcome` => expected status, attempt, metric, audit.
- Callback: event type x current status ordering (rank rule), duplicates, conflict.
- Idempotency: sequential/concurrent/different body/expiry.

## Concurrency testing
`ExecutorService` + `CountDownLatch` start gates; repeat N times; assert uniqueness invariants (one row), CAS losers acked. Property: run key races at least 20 iterations in CI (cost small).

## Failure injection
`FaultInjector` modes `{NONE,TRANSIENT,PERMANENT,RATE_LIMIT,TIMEOUT,AMBIGUOUS,ALTERNATING}` with probability; datasource/coordination wrappers throwing; test hook for simulated crash points.

## Test data
Synthetic seed (2 tenants, 60 recipients, 8 templates). Builders over fixtures; no real data.

## Coverage and quality gates (targets)
Line coverage target (illustrative) >= 80% on `domain`, `service`, `callback`, `messaging`; mutation testing (PIT) optional on `domain` and `RetryPolicy` (N/E). Flaky test policy: quarantine within one day, fix within sprint.

## Definition of "insufficient tests" (review smell)
Only happy path; mocks everywhere and no integration; assertions on logs instead of state; no concurrent test for a race fix; sleeping.

## Traceability
Acceptance criteria ids (`acceptance-criteria.md`) mapped to tests in `docs/traceability` (generated later by the lead).
