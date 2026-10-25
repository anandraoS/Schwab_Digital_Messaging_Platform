# 22. Failure and Recovery Scenarios

## 22.1 Designing for failure

A messaging platform is judged by what it does when things go wrong. The design is organised around **ten failure scenarios**, each analysed along the same five axes and each tied to a named automated test:

| Axis | Question |
|---|---|
| Detection | Which metric, alert or API view tells an operator? |
| Customer impact | What does a customer or client actually experience? |
| Recovery | What brings the system back, automatically or by operator action? |
| Data consistency | What invariant holds throughout (no loss, no duplicate, no regression)? |
| Prevention | Which design decision limits the blast radius? |
| Proof | Which test demonstrates it? |

The test methods below are real: they exist in `backend/src/test/java/com/example/messaging/pipeline/FailureRecoveryIT.java` (scenarios 1 to 8 and 10) and `backend/src/test/java/com/example/messaging/batch/FailureRecoveryBulkIT.java` (scenario 9). Whether they pass is stated only in `docs/validation/VALIDATION_REPORT.md`, never in the design text.

{{diagram:failureWindows}}

### Evidence from the executed end-to-end run

The real HTTP end-to-end run recorded in `docs/validation/e2e-run-output.txt` (Appendix E; 254 passed, 0 failed, 0 skipped) exercised several of these scenarios against the packaged jar on the `local` profile. Its section 8 shows, as PASS lines: transient failure to `RETRY_PENDING` and then delivery after the fault cleared; permanent failure with exactly one attempt; provider timeout to `DELIVERY_UNKNOWN` with exactly one attempt while unknown; reconciliation finding `NOT_RECEIVED` and safely re-sending; an ambiguous answer reconciled as still accepted (no duplicate send) and resolved by a late `DELIVERED` callback; rate limiting (429) retried; six SMS through a 2 per second limiter all dispatched; an outage ending in `DEAD_LETTERED` after 5 attempts and an operator replay after recovery. Section 9 shows duplicate, replayed, out-of-order and conflicting callbacks, and section 11 shows the bulk stop and restart finishing a 20,000-row job exactly once. Scenarios 5 to 7 (a worker crash, a database outage, a Redis outage) are simulated in-JVM only and are not part of that HTTP run.

## 22.2 Scenario summary

| # | Scenario | Detection | Recovery | Consistency invariant | Proof |
|---|---|---|---|---|---|
| 1 | Provider outage then recovery | `ProviderCircuitOpen`, `ProviderErrorRatioHigh`, rising `messaging.retry.scheduled` | Breaker half-opens; exhausted messages `DEAD_LETTERED`; operator replay | One attempt row per try; no status skipping | `FailureRecoveryIT#scenario01_providerOutage_retriesWithBackoff_thenDeadLetters_thenReplay` |
| 2 | Duplicate API submission | `messaging.idempotency.hits`; `Idempotent-Replay: true` | Automatic | Exactly one message and one idempotency row per (tenant, key) | `#scenario02_duplicateSubmission_sameKey_returnsOriginal_noSecondMessage` |
| 3 | Duplicate and out-of-order callbacks | `messaging.callbacks{result}` | Automatic: ignore or record `applied=false` | Status never regresses; first terminal wins | `#scenario03_duplicateAndOutOfOrderCallbacks_applyMonotonically`, `#scenario03b_receiptThatOvertakesTheConsumerIsApplied` |
| 4 | Provider timeout or ambiguous result | `messaging.transitions{to="DELIVERY_UNKNOWN"}`, `GET /failed-messages` | Late callback or `lookup`; "not received" triggers safe retry | No blind resend; provider call count stays 1 until lookup says not received | `#scenario04_providerTimeout_movesToDeliveryUnknown_reconcilesWithoutDuplicateSend` |
| 5 | Consumer crash after provider acceptance | `PROCESSING` older than lock TTL; sweeper transition | `StuckMessageSweeper` then callback or lookup | Attempt row `STARTED` remains; not retried blindly | `#scenario05_consumerCrashAfterProviderAccept_sweeperMarksDeliveryUnknown`, `#scenario05b_sentToProviderWithoutReceiptBecomesUnknownAfterTheReceiptTimeout` |
| 6 | Database outage | `hikaricp_connections_pending`, readiness DOWN, 503 rate | Connections recover; publisher resumes; nacked messages redelivered | Fail closed: no 202 without commit | `#scenario06_databaseOutage_submissionFailsClosed_thenRecovers` |
| 7 | Redis outage | `messaging.redis.fallback`, Lettuce errors | `ResilientCoordination` falls back and switches back | Idempotency still enforced by the DB unique key | `#scenario07_redisOutage_fallsBackToDb_noDuplicates` |
| 8 | Poison message | `messaging.deadletter.total{reason="POISON_MESSAGE"}` | Operator inspects, fixes, replays or discards | `DEAD_LETTERED` with bounded redeliveries | `#scenario08_poisonMessage_goesToDlq_noInfiniteLoop` |
| 9 | Bulk job interrupted and restarted | `messaging.bulk.progress{jobId}` flat | `POST /bulk-jobs/{id}/restart` | One message per valid row; counters reconcile | `FailureRecoveryBulkIT#scenario09_bulkJobRestart_resumesWithoutDuplicates` |
| 10 | Provider rate limit, backpressure, backlog | `messaging.provider.calls{outcome="RATE_LIMITED"}`, `messaging.queue.depth`, `messaging.ratelimit.rejected` | Token bucket paces; backlog drains; 503 + `Retry-After` beyond the threshold | Rate-limit requeue consumes no attempt; no loss | `#scenario10_providerRateLimitAndBackpressure_noAttemptConsumed_apiReturns503` |

## 22.3 How the scenarios are driven

`FailureRecoveryIT` extends the pipeline test base (`AbstractPipelineIT`) and runs the **real asynchronous pipeline** with simulators. The faults are injected by test seams that production code exposes without weakening it:

| Seam | Used for |
|---|---|
| `FaultInjector` modes (`messaging.simulator.*`) | Transient, permanent, rate limit, timeout, ambiguous responses |
| Adjustable `Clock` (system time plus an offset) | Crossing lock TTLs, receipt timeouts and `reconcile-after` without sleeping |
| Programmable `DispatchHook` | Worker "death" after the provider call; injected processing failures |
| `DataSource` wrapper that fails `getConnection` | Database outage (scenario 6) |
| Switchable coordination backend wrapped by the production `ResilientCoordination` | Redis outage (scenario 7) |

Backoff is shortened to a 40 ms base and 200 ms cap with `max-attempts` 4 in that context; production defaults are in `application.yml`. Scenario scripts in `tests/failure-recovery/` (`provider-outage.mjs`, `callback-replay.mjs`, `provider-timeout.mjs`) replay scenarios 1, 3 and 4 against a started `local` application on port 18080. Scenario 5 (a simulated crash), 6 and 7 are in-JVM only; scenario 9 is owned by the Spring Batch module. (The index table in the embedded design document lists a bulk script as a counterpart of scenario 9; only the three scripts named above exist in `tests/failure-recovery/`.)

> [!NOTE] Not claimed
> RabbitMQ, Redis and PostgreSQL failures are simulated at the port level (a failing `DataSource`, a failing coordination stub). A real broker partition or a real PostgreSQL failover was **not** exercised; those belong to the `-Pcontainers` tests and to game days in a real environment.

## 22.4 Operator playbooks

Every alert links to a runbook in `docs/operations/runbooks/`; Appendix D reproduces them. The mapping from scenario to runbook:

| Scenario | Runbooks |
|---|---|
| 1 Provider outage | `provider-outage.md`, `provider-rate-limit.md` |
| 3, 4, 5 Callbacks and ambiguity | `callback-signature-failures.md`, `consumer-crash-ambiguous-outcomes.md` |
| 6 Database outage | `database-outage.md` |
| 7 Redis outage | `redis-outage.md` |
| 8 Poison and dead letters | `dlq-growth.md` |
| 9 Bulk | `bulk-job-stuck.md`, `bulk-job-restart.md` |
| 10 Backlog and retry storms | `queue-backlog.md`, `retry-storm.md`, `high-latency-and-resource-saturation.md` |

## Reference material

The full scenario analysis follows exactly as written in the repository, including the detailed proof for each test.

{{embed:docs/architecture/reliability-scenarios.md|title=Reliability scenarios (full analysis)}}

{{embed:docs/leadership/troubleshooting-scenarios.md|title=Troubleshooting scenarios}}
