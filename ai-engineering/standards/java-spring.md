# Java / Spring Standards

Java 21, Spring Boot 3.5.16, Maven. Applies to `backend/**`.

## Structure
- Package by responsibility per CONTRACT section 2. Controllers are thin (map DTO, call service, map response). Business rules live in `service`/`domain`.
- `domain` state machine and `template` renderer are pure Java (no Spring) so they unit-test fast.
- Constructor injection only; `final` fields; no field injection, no static state.
- DTOs are `record`s with Bean Validation annotations; entities never leak through the API.
- Configuration through `@ConfigurationProperties` records (`PropertiesConfig`), validated (`@Validated`), keys `messaging.*`.
- Time: inject `java.time.Clock`; never `Instant.now()`/`LocalDateTime.now()` in logic. Randomness: inject `RandomGenerator`.
- Ids via `common.Ids`.

## Transactions
- `@Transactional` on service methods only; keep short; never around remote calls; explicit `readOnly=true` for queries.
- Status changes via CAS repository methods and `MessageStateMachine`; no entity setters for status outside the transition service.
- Do not catch `DataIntegrityViolationException` inside the transaction that must continue (PostgreSQL aborts it); isolate in a new transaction or check beforehand.

## Errors
- Throw `ApiException` subclasses with an `ErrorCode`; `GlobalExceptionHandler` maps. No `catch (Exception)` that swallows; if catching, log with correlation id and rethrow or convert.
- Never return stack traces. No `e.printStackTrace()`.

## Concurrency
- Bounded `ThreadPoolExecutor`s with named threads and explicit rejection policy; no `newCachedThreadPool`, no parallel streams on shared pools.
- Timeouts on every remote call (connect and read). Resilience via `ResilientProviderInvoker` only.
- Scheduled methods catch all exceptions per tick and increment a failure meter.
- Virtual threads are not enabled by default (see source config); if introduced, ADR required (pinning, pool semantics).

## Logging
- SLF4J, parameterized messages, MDC for `correlationId`/`tenant`/`messageId`; no bodies/addresses/secrets; no string concatenation in log calls; `Redaction` for structures.

## Data access
- Spring Data JPA; fetch joins/`@EntityGraph`/projections for lists; no lazy access in mappers; paging for any list; no `findAll()` on large tables; tenant parameter required.

## Security
- Method-level checks plus `AuthorizationService`; validate and bound all inputs; no reflection/expression evaluation on user input.

## Style
- Google-style formatting (2-space or project formatter config: follow existing files), meaningful names, small methods, no commented-out code, no TODO without ticket reference.
- Javadoc on public ports and non-obvious invariants (why, not what).
- Prefer `Optional` returns for lookups, not null; use `List.of` immutables for DTOs.

## Testing
See `context/testing.md`. JUnit 5, AssertJ; Mockito for boundaries only; deterministic.
