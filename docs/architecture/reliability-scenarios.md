# Reliability Scenarios

Ten failure scenarios, each with detection, customer impact, recovery, data consistency, prevention and proving test. The test names below are the **required names** for the test-writing agent: class `FailureRecoveryIT` (`backend/src/test/java/com/example/messaging/...`, extends `AbstractIntegrationTest`, profile `test`, simulators driven by `FaultInjector`). Whether a test exists/passes is stated only in `docs/validation/VALIDATION_REPORT.md`, not here.

## Test index

| # | Scenario | Test (method in `FailureRecoveryIT`) | Script/e2e counterpart |
|---|---|---|---|
| 1 | Provider outage then recovery | `scenario01_providerOutage_retriesWithBackoff_thenDeadLetters_thenReplay` | `tests/failure-recovery` provider-outage script |
| 2 | Duplicate API submission (client retry) | `scenario02_duplicateSubmission_sameKey_returnsOriginal_noSecondMessage` | `tests/api-http` idempotency requests |
| 3 | Duplicate and out-of-order callbacks | `scenario03_duplicateAndOutOfOrderCallbacks_applyMonotonically` | `tests/failure-recovery` callback replay script |
| 4 | Provider timeout / ambiguous result | `scenario04_providerTimeout_movesToDeliveryUnknown_reconcilesWithoutDuplicateSend` | `tests/failure-recovery` |
| 5 | Consumer crash after provider acceptance | `scenario05_consumerCrashAfterProviderAccept_sweeperMarksDeliveryUnknown` | in-JVM only (simulated crash) |
| 6 | Database outage during submission / state update | `scenario06_databaseOutage_submissionFailsClosed_thenRecovers` | in-JVM (datasource fault wrapper) |
| 7 | Redis outage | `scenario07_redisOutage_fallsBackToDb_noDuplicates` | in-JVM (failing coordination stub) |
| 8 | Poison message | `scenario08_poisonMessage_goesToDlq_noInfiniteLoop` | in-JVM |
| 9 | Bulk job interrupted and restarted | `scenario09_bulkJobRestart_resumesWithoutDuplicates` (**owned by the Spring Batch module**, not present in the messaging-core `FailureRecoveryIT`) | `tests/failure-recovery` bulk script |
| 10 | Provider rate limiting, backpressure and queue backlog | `scenario10_providerRateLimitAndBackpressure_noAttemptConsumed_apiReturns503` | `performance/jvm-harness` (optional) |

Supporting tests: `RetryPolicyTest`, `MessageStateMachineTest`, `SignatureVerifierTest`, `ReplayGuardTest`, `StuckMessageSweeperTest`, `InMemoryMessageBusTest`, `GracefulShutdownTest`, `ResilientProviderInvokerTest`, `EventNormalizerTest`, `RabbitBusSupportTest` / `RabbitTopologyTest` (pure parts of the Rabbit bus; the broker itself is covered only by `RabbitMessageBusContainerIT`, a Testcontainers test that needs Docker and is **not** part of the default build), and the cross-cutting ITs `ChannelDispatchIT`, `ProviderOutcomeIT`, `CallbackApiIT`, `IdempotentConsumerIT`, `ConcurrencyIT`, `CancelRaceIT`, `BackpressureIT`, `ReconciliationIT`, `ReplayServiceIT`, `LoggingPrivacyIT`.

How the scenarios are driven (all in `FailureRecoveryIT`, which runs the real asynchronous pipeline): simulators with `FaultInjector` modes (`messaging.simulator.*`), an adjustable `Clock` (system time plus an offset, so lock TTLs, receipt timeouts and `reconcile-after` can be crossed without sleeping), a programmable `DispatchHook` (worker "death" after the provider call, injected processing failures), a `DataSource` wrapper that fails `getConnection` (scenario 6) and a switchable coordination backend wrapped by the production `ResilientCoordination` (scenario 7). Backoff is shortened to 40 ms base / 200 ms cap and `max-attempts` is 4 in that context; the production defaults are in `application.yml`.

---

## 1. Provider outage then recovery
| Aspect | Detail |
|---|---|
| Detection | `messaging.provider.calls{outcome="TRANSIENT_FAILURE"}` rate up; `messaging.provider.circuit.state` = 2; `messaging.retry.scheduled` rising; alert `ProviderSubmissionSuccessLow`, `CircuitOpen` |
| Customer impact | Delay for non-critical; CRITICAL may use fallback channel if the request allows and consent permits |
| Recovery | Breaker half-opens, retries succeed; messages beyond `max-attempts` are `DEAD_LETTERED(RETRIES_EXHAUSTED)`; after recovery operators replay (`POST /dead-letters/{id}/replay`) |
| Data consistency | One `message_attempt` per try; no status skips (state machine); attempt_count matches attempts |
| Prevention | Breaker + backoff + jitter (ADR-0006), provider failover via `ProviderRegistry`, runbook `provider-outage.md` |
| Proof | `FailureRecoveryIT#scenario01_...`: fault mode TRANSIENT, assert backoff windows (the gap before attempt n+1 is at least half of the exponential ceiling), DLQ after N attempts (4 in the test context), `messaging.retry.scheduled` +3 and `messaging.deadletter.total{RETRIES_EXHAUSTED}` +1, legal transitions only; then fault NONE and `POST /dead-letters/{id}/replay` (policy pre-check audited, fresh attempt budget) reaches `SENT_TO_PROVIDER` and, after a callback, `DELIVERED` |

## 2. Duplicate API submission
| Detection | `messaging.idempotency.hits` counter; HTTP `Idempotent-Replay: true` |
|---|---|
| Customer impact | None (single message) |
| Recovery | Automatic |
| Data consistency | Exactly one `message_request` and one `idempotency_record` per `(tenant, key)`; parallel duplicates yield 200/409 `IDEMPOTENCY_IN_PROGRESS`, never two rows |
| Prevention | ADR-0004, UNIQUE(tenant_id, idempotency_key) |
| Proof | `scenario02_...`: sequential and concurrent (N threads) identical POSTs; different body same key gives 422 `IDEMPOTENCY_KEY_REUSED` |

## 3. Duplicate and out-of-order callbacks
| Detection | `messaging.callbacks{result="duplicate"|"out_of_order"|"stale"}` |
|---|---|
| Customer impact | None |
| Recovery | Automatic: duplicates ignored, lower-rank events recorded `applied=false` |
| Data consistency | Status never regresses; DELIVERED then BOUNCED keeps DELIVERED + audit `DELIVERY_EVENT_CONFLICT`; `delivery_event` unique key prevents duplicate rows |
| Prevention | CONTRACT 3.4 rank rule, `ReplayGuard`, unique constraint |
| Proof | `scenario03_...`: DELIVERED arrives before SENT; the late SENT is `out_of_order`; the same `X-Event-Id` again is answered `200 replayed` with no new row; a second DELIVERED under a new id is `stale`; BOUNCED after DELIVERED is a `conflict` with audit `DELIVERY_EVENT_CONFLICT`; the same payload event id behind two transport ids is a `duplicate` (unique key). `scenario03b_...`: a receipt that overtakes the consumer (message still PROCESSING) is applied and the redundant envelope is skipped. Final status and `applied` / `ignored_reason` / `messaging.callbacks{result}` are asserted |

## 4. Provider timeout / ambiguous result
| Detection | `messaging.transitions{to="DELIVERY_UNKNOWN"}`, `messaging.provider.calls{outcome="TIMEOUT"}`; `GET /failed-messages` |
|---|---|
| Customer impact | Possible delay; possible (not certain) delivery |
| Recovery | Late callback or `ReconciliationService.lookup`; "not received" triggers safe retry with same `idempotencyRef` |
| Data consistency | No blind resend: provider call count asserted == 1 until lookup says not received |
| Prevention | ADR-0010, provider idempotency ref |
| Proof | `scenario04_...`: (a) TIMEOUT: DELIVERY_UNKNOWN, provider call count stays 1, `reconcileOne` finds NOT_RECEIVED and exactly one more call is made; (b) AMBIGUOUS: lookup ACCEPTED keeps it unknown, a late DELIVERED callback wins, no second call; (c) lookup DELIVERED is applied with a `recon:` evidence event; (d) lookup FAILED is applied; (e) PUSH is retried and the provider deduplicates on the idempotency reference (2 calls, 1 accepted message) |

## 5. Consumer crash after provider acceptance
| Detection | PROCESSING older than `lock-ttl` (sweeper), `messaging.transitions{to="DELIVERY_UNKNOWN"}` after restart |
|---|---|
| Customer impact | Likely delivered; platform status lags |
| Recovery | `StuckMessageSweeper` to DELIVERY_UNKNOWN, then callback/lookup |
| Data consistency | `message_attempt` row `STARTED` exists with null outcome; not retried blindly |
| Prevention | Attempt row before call, lock TTL, ADR-0010 |
| Proof | `scenario05_...`: the simulator accepts, then a `DispatchHook` kills the worker before the outcome is persisted; the message stays PROCESSING with attempt row `STARTED` (outcome null) and the redelivery is skipped by the consumer dedupe; before the lock expires the sweeper does nothing; after `Clock` +3 min (lock TTL 2 min) the sweeper moves it to DELIVERY_UNKNOWN (`STUCK_SWEEP` audit) and the provider call count stays 1; reconciliation then finds ACCEPTED and the late receipt resolves it. `scenario05b_...`: SENT_TO_PROVIDER without a receipt past `receipt-timeout` (15 min) becomes DELIVERY_UNKNOWN |

## 6. Database outage
| Detection | `hikaricp_connections_pending`, `hikaricp_connections_timeout_total`, `/actuator/health` readiness DOWN, HTTP 503 rate |
|---|---|
| Customer impact | New submissions fail with 503 (clients retry with same key); in-flight dispatch pauses |
| Recovery | Connections recover; outbox publisher resumes; consumers' nacked messages redelivered |
| Data consistency | Fail closed: no 202 without commit (outbox in same tx); no ghost publishes; consumer cannot record outcome so it nacks/requeues; if provider call already made, becomes DELIVERY_UNKNOWN via sweeper |
| Prevention | Short Hikari `connectionTimeout`, readiness gating, retry with same idempotency key, HA database |
| Proof | `scenario06_...`: a wrapper `DataSource` throws on `getConnection` while a submit arrives: assert 503 `SERVICE_UNAVAILABLE` with `Retry-After`, no `message_request` and no `idempotency_record` row, schedulers keep running without crashing; after the restore the same key yields exactly one message (202) and a further attempt is the idempotent replay (200) |

## 7. Redis outage
| Detection | `messaging.redis.fallback` counter, Lettuce errors |
|---|---|
| Customer impact | None to slight latency (DB fallback); rate limiting degrades to in-memory per instance |
| Recovery | `ResilientCoordination` switches back when healthy |
| Data consistency | Idempotency still enforced by DB unique key; token bucket may over-admit slightly across instances (documented) |
| Prevention | Redis is fast-path only (ADR-0004) |
| Proof | `scenario07_...`: the coordination backend throws (wrapped by the production `ResilientCoordination`); duplicate submissions are still deduplicated by the database (202 then 200 replay, one row), dispatch still passes the provider rate limiter through the local fallback, `messaging.redis.fallback` increases; after recovery and the cool-down the counter stops moving |

## 8. Poison message
| Detection | `messaging.deadletter.total{reason="POISON_MESSAGE"}`, `messaging.queue.dlq.depth` |
|---|---|
| Customer impact | None for others; that message needs operator attention |
| Recovery | Operator inspects, fixes cause, replays or discards |
| Data consistency | Status `DEAD_LETTERED`, audit with reason; bounded redeliveries (<= `poison-threshold`) |
| Prevention | Poison threshold; schema-tolerant envelope |
| Proof | `scenario08_...`: (a) an envelope for a message that does not exist and an incomplete envelope are dead-lettered on their first delivery; (b) a real message whose processing keeps throwing is delivered exactly `poison-threshold` (3) times, then lands in the bus DLQ and is mirrored in the database as `DEAD_LETTERED(POISON_MESSAGE)` through legal transitions; the consumer keeps serving other messages. (An unreadable Rabbit body goes straight to the DLQ: unit-tested for the pure part, broker part only in the container IT.) |

## 9. Bulk job interrupted and restarted
| Detection | `messaging.bulk.progress{jobId}` flat, job status RUNNING with stale step heartbeat |
|---|---|
| Customer impact | Campaign delay |
| Recovery | `POST /bulk-jobs/{id}/restart` resumes from last committed chunk |
| Data consistency | Deterministic keys `bulk-<jobId>-<externalRef>`: exactly one message per row after restart; counts reconcile (`processed = succeeded + failed + duplicate`) |
| Prevention | Chunk transactions, restartable job repository, skip limit |
| Proof | `scenario09_...`: generate synthetic CSV, fail writer mid-chunk, restart, count messages == valid unique rows |

## 10. Provider rate limit, backpressure, backlog
| Detection | `messaging.provider.calls{outcome="RATE_LIMITED"}`, `messaging.queue.depth`, oldest message age, `messaging.ratelimit.rejected` |
|---|---|
| Customer impact | Higher dispatch delay for LOW/NORMAL; CRITICAL prioritized; API returns 503 + `Retry-After` beyond backpressure threshold |
| Recovery | Token bucket paces; backlog drains; scale consumers only if provider limit allows |
| Data consistency | Rate-limit requeue consumes no attempt; no loss; priority order respected |
| Prevention | Token bucket, `max-queue-depth`, priority aging (spec 0001) |
| Proof | `scenario10_...`: (a) provider limit 2/s, six messages: `messaging.ratelimit.rejected{scope="provider"}` increases, every message ends with `attempt_count=1` and one attempt row, exactly six provider calls; (b) consumers paused and a backlog limit of 3: the API answers 503 `SERVICE_UNAVAILABLE` + `Retry-After` and persists nothing for the refused request, accepted messages are not lost, and the refused client's retry with the same key succeeds |

## How to run (once implemented)
`bash scripts/it.sh FailureRecoveryIT` (fast loop: compiles and runs only the named failsafe tests) or the full `bash scripts/mvn.sh clean verify`. Results are stated only in the validation report. The scripts in `tests/failure-recovery/` (`provider-outage`, `callback-replay`, `provider-timeout`) replay scenarios 1, 3 and 4 against a started application (profile `local`, port 18080).
