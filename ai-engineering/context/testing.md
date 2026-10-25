# Context: Testing
Load when: writing/reviewing tests. Detail: `docs/leadership/test-strategy.md`, `docs/architecture/reliability-scenarios.md`.

- Layout: tests mirror packages under `backend/src/test/java`. Integration tests extend `AbstractIntegrationTest` (`@SpringBootTest`, profile `test`, unique H2 db per context, deterministic simulators).
- Container tests: `*ContainerIT` with `@Testcontainers(disabledWithoutDocker = true)`, Maven profile `-Pcontainers` (not executed in the authoring environment).
- Failure scenarios (class `FailureRecoveryIT`): `scenario01_providerOutage_retriesWithBackoff_thenDeadLetters_thenReplay`, `scenario02_duplicateSubmission_sameKey_returnsOriginal_noSecondMessage`, `scenario03_duplicateAndOutOfOrderCallbacks_applyMonotonically`, `scenario04_providerTimeout_movesToDeliveryUnknown_reconcilesWithoutDuplicateSend`, `scenario05_consumerCrashAfterProviderAccept_sweeperMarksDeliveryUnknown`, `scenario06_databaseOutage_submissionFailsClosed_thenRecovers`, `scenario07_redisOutage_fallsBackToDb_noDuplicates`, `scenario08_poisonMessage_goesToDlq_noInfiniteLoop`, `scenario09_bulkJobRestart_resumesWithoutDuplicates`, `scenario10_providerRateLimitAndBackpressure_noAttemptConsumed_apiReturns503`.
- Determinism: inject `Clock` and `RandomGenerator`; no `Thread.sleep` (use await with timeout); `FaultInjector` modes NONE, TRANSIENT, PERMANENT, RATE_LIMIT, TIMEOUT, AMBIGUOUS, ALTERNATING.
- Concurrency tests: latch start gate, N >= 20 threads, assert invariants (single row, CAS loser acked).
- Matrices required: state machine pairs, policy combos, role x endpoint x tenant, outcome mapping, callback ordering.
- Assert state and counters (DB rows, metrics), not log lines.
- Frontend: Karma + Jasmine for services, interceptors, guards, key components.
- Report results only from executed runs (`docs/validation/VALIDATION_REPORT.md`).
