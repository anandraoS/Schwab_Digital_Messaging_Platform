# Code Review Scenarios

Six scenarios drawn from the failure classes of this platform. Each: problem (what the PR does), investigation (what the reviewer checks), solution, trade-offs, regression tests. Code is illustrative; class names follow CONTRACT.

## CR-1: Retry on timeout "to improve reliability"
**Problem.** PR changes `DispatchService` so `TIMEOUT` maps to `RETRY_PENDING`:
```java
case TIMEOUT -> transition(msg, RETRY_PENDING);   // "just retry, simpler"
```
**Investigation.** Does a timeout mean the provider did not accept? No. Read ADR-0010; check channel (SMS). Ask: what prevents two SMS? The provider idempotency ref only helps if the vendor honors it. Check `FaultInjector` AMBIGUOUS test: provider call count.
**Solution.** Map to `DELIVERY_UNKNOWN`; reconciliation decides; PUSH may retry (collapse key):
```java
case TIMEOUT, AMBIGUOUS -> channel == PUSH ? retry(msg) : transition(msg, DELIVERY_UNKNOWN);
```
**Trade-offs.** Slower recovery for truly lost sends vs duplicate risk. Chosen: no duplicate; bounded delay via `reconcile-after`.
**Regression tests.** `FailureRecoveryIT#scenario04_...` (provider call count == 1 until lookup says not received); `DispatchServiceTest` outcome table.

## CR-2: Status update without compare-and-set
**Problem.** Callback processor does `msg.setStatus(DELIVERED); repo.save(msg);` after reading the entity.
**Investigation.** Concurrent actors: sweeper marks DELIVERY_UNKNOWN, operator cancels, consumer finishes. Read-modify-write lets the last writer win and bypasses `MessageStateMachine`; `@Version` would throw but the handler catches and ignores? Look for swallowed `ObjectOptimisticLockingFailureException`.
**Solution.** Use CAS: `int updated = repo.casStatus(id, expectedFrom, DELIVERED, now)`; if 0, reload and re-evaluate by rank rule (not blind retry).
**Trade-offs.** One more query on conflict; much stronger invariants.
**Tests.** Concurrent callback vs sweeper race test; `MessageStateMachineTest` matrix; assert no direct `setStatus` usage via ArchUnit-style test.

## CR-3: "Find by id" for convenience
**Problem.** New endpoint `GET /api/v1/delivery-events/{id}` uses `repo.findById(id)`.
**Investigation.** Where does tenant come from? Not applied. Any tenant can enumerate. Check also response mapping for masked fields.
**Solution.** `findByIdAndTenantId(id, ctx.tenantId())`; foreign => 404; PLATFORM_ADMIN scoped via header.
**Trade-offs.** Slightly more repository methods; prevents BOLA.
**Tests.** Cross-tenant matrix entry for the endpoint expecting 404; role test.

## CR-4: Unbounded executor for provider calls
**Problem.** `Executors.newCachedThreadPool()` used by `ResilientProviderInvoker` "so calls never queue".
**Investigation.** Under provider slowness each in-flight call parks a thread; cached pool grows without bound => OOM/thread exhaustion, and the breaker's time limiter cancels futures but threads blocked on sockets remain.
**Solution.** Bounded `ThreadPoolExecutor` (core=max=concurrency+margin, small queue, `AbortPolicy` mapped to `RATE_LIMITED`/requeue), socket-level read timeouts in adapter, metric for rejections.
**Trade-offs.** Rejections under overload (desired backpressure) instead of cascading failure.
**Tests.** Load test with TIMEOUT fault: thread count stays bounded; `FailureRecoveryIT#scenario10_...`.

## CR-5: N+1 in message list
**Problem.** `GET /messages` maps entities to DTOs; mapping touches `message.getRecipient().getEmail()` and `message.getAttempts().size()` lazily.
**Investigation.** Enable statistics in a test: 1 + 2N queries for page size N. Also PII: mapper calls email getter.
**Solution.** Projection/DTO query joining recipient (masked in SQL or mapper), `@EntityGraph` or batch size; counts via subquery or denormalized `attempt_count` (already a column).
**Trade-offs.** Dedicated query vs entity reuse; faster, slightly more code.
**Tests.** Statement-count assertion (<= 3) for page size 50; masking test.

## CR-6: Logging the payload on failure
**Problem.** `log.error("Provider failed for {} with request {}", messageId, providerRequest)`.
**Investigation.** `providerRequest` contains phone number and rendered body.
**Solution.** Log ids and outcome codes; use `Redaction` for any structure; put sanitized snippet in diagnostics store (Mongo) with TTL.
**Trade-offs.** Less detail in logs; more privacy; diagnostics endpoint for operators.
**Tests.** Log capture test asserting absence of `+1555` / `@example.test` patterns during a failing send.

## CR-7 (bonus): Idempotency check after side effect
**Problem.** Service sends to provider first, then writes `idempotency_record`.
**Solution.** Claim first (unique insert), act, complete; crash between leaves IN_PROGRESS which the TTL/sweeper resolves.
**Tests.** `scenario02_...` plus crash-between test.
