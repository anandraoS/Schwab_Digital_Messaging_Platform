---
applyTo: "backend/src/main/java/**/*.java"
---
# Java / Spring rules (main code)

Full standard: `ai-engineering/standards/java-spring.md`. Lifecycle: `ai-engineering/context/lifecycle.md`.

- Constructor injection, final fields, records for DTOs and configuration properties (`messaging.*`).
- Inject `java.time.Clock` and `RandomGenerator`; never call `Instant.now()` or `Math.random()` in logic.
- Status changes only through `MessageStateMachine` and CAS repository methods (`UPDATE ... WHERE id=? AND status=?`, check affected rows). No `setStatus` outside the transition service.
- `@Transactional` on service methods only, short; never around provider or other remote calls.
- Tenant-scoped repository methods (`findByIdAndTenantId`); foreign tenant resource means `NotFoundException` (404).
- Provider calls only via `ResilientProviderInvoker`; map failures to `ProviderOutcome`; unknown/after-send errors are AMBIGUOUS; no retries inside adapters.
- Bounded `ThreadPoolExecutor` (named threads, explicit rejection policy); no `newCachedThreadPool`; no parallel streams on shared pools.
- Errors: throw `ApiException` subclasses with `ErrorCode`; no swallowed exceptions; no stack traces to clients.
- Logging: SLF4J parameterized; ids only; never bodies, full addresses, secrets, tokens, signatures; use `Redaction`.
- Metrics: only through `MessagingMetrics` with CONTRACT section 8 names; no unbounded tag values.
- Fetch lists with projections or `@EntityGraph`; no lazy access in mappers; always paginate.
