# Skill: Write a Failure-Recovery Test

**When**: implementing or extending `FailureRecoveryIT`. **Load**: `context/testing.md`, `docs/architecture/reliability-scenarios.md`.

## Steps
1. Pick the scenario method name from the reliability table (exact name).
2. Arrange: synthetic tenant/recipient/template from seed; deterministic `Clock` (fixed, advanceable) and seeded random; set provider fault mode through `FaultInjector` (NONE, TRANSIENT, PERMANENT, RATE_LIMIT, TIMEOUT, AMBIGUOUS, ALTERNATING).
3. Act through the public API (`POST /messages`) where possible; drive time by advancing the clock and invoking the scheduler beans' tick methods or awaiting with timeouts.
4. Assert state, not implementation: final `MessageStatus`, `message_attempt` rows and outcomes, `attempt_count`, provider call count (spy on simulator), audit events, metric counters, DLQ depth.
5. Assert invariants: no duplicate provider call where forbidden; no illegal transition (every observed transition is in the table).
6. Crash simulation: test hook that throws after provider call and before persisting; then run `StuckMessageSweeper` after advancing the clock beyond lock TTL.
7. Concurrency: latch gate, >= 20 threads.
8. Cleanup: unique H2 per context; shut down executors.
9. Run only that test (`mvn -B -Dtest=FailureRecoveryIT#scenarioNN* test`) then the class; record result.

## Guardrails
No `Thread.sleep`; no assertions on log text; do not weaken an assertion to pass; mark Docker-dependent variants `*ContainerIT`.
