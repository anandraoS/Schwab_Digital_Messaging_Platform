# Low-Level Design

Class-level view for the critical paths. Names follow CONTRACT section 2; method signatures are design intent: confirm in source before relying on them.

## 1. Domain
- `MessageStatus` enum, `MessageStateMachine`: `static boolean canTransition(from,to)`, `MessageStatus require(from,to)` throws `IllegalStateTransitionException`. Table-driven `EnumMap<MessageStatus, EnumSet<MessageStatus>>`.
- `MessageRequest` entity (`@Version`): setters for status are package-private; transitions only through `MessageTransitions` (service) using CAS repository method `int casStatus(id, from, to, reason, now)`.

## 2. Submission (`NotificationService.submit`)
```
@Transactional
submit(principal, idemKey, request):
  claim = idempotencyService.claim(tenant, idemKey, hash(request))  // returns REPLAY / NEW / CONFLICT / IN_PROGRESS
  recipient = recipientRepo.findByIdAndTenantId(...)  // 404 if absent
  decision = policyService.evaluate(...)
  tplVersion = templateService.resolvePublished(tenant, code, locale, version)
  variableValidator.validate(tplVersion, variables)
  msg = MessageRequest.accepted(...) ; transition(ACCEPTED -> VALIDATED)
  outboxRepo.save(OutboxEvent.dispatch(msg)) ; auditService.record(...)
  idempotencyService.complete(claim, response)
```
## 3. Outbox publisher
```
@Scheduled(fixedDelay=200)
publishBatch():
  ids = outboxRepo.claimBatch(limit, now)           // CAS NEW -> CLAIMED (or equivalent, see source)
  for each: bus.publish(envelope) ; casStatus(message, VALIDATED, QUEUED) ; outbox SENT
  on failure: release claim (leave NEW) + metric; no exception escapes the scheduler thread
```
## 4. Dispatch (`DispatchConsumer.onMessage` -> `DispatchService.process`)
```
onMessage(env):
  if (!processedMessage.tryInsert(env.dispatchId)) { ack; return; }
  if (!repo.casStatus(env.messageId, QUEUED, PROCESSING)) { ack; return; }
  try { dispatchService.process(env); ack }
  catch (PoisonException e)    { dlq(POISON_MESSAGE); ack }
  catch (RuntimeException e)   { failureCounter.increment(); nackRequeueOrDlq(env) }
process(env):
  msg = load ; policy re-check ; rendered = renderer.render(pinned) ; 
  if (!limiter.tryAcquire(provider)) { bus.requeueWithDelay(env, shortDelay); revertToQueued; return; }
  attempt = attemptRepo.start(msg, attemptNo)               // STARTED, committed
  result = invoker.send(provider, request, idempotencyRef)  // timeout + breaker, NO transaction
  finish(attempt, result) ; apply(result)                   // new short transaction
```
## 5. Retry policy
```
delayMs(n) = min(capMs, baseMs * 2^(n-1));  nextAt = now + delayMs(n) * (0.5 + 0.5*random.nextDouble())
```
`RetryPolicy` receives `Clock` and `RandomGenerator` for deterministic tests.

## 6. Callback processing
`CallbackController` reads raw bytes (`@RequestBody byte[]`), verifies signature, then normalizer, then `DeliveryEventProcessor.apply(event)`:
```
@Transactional apply(e):
  if (!eventRepo.insertIfAbsent(e)) return DUPLICATE
  msg = locate(e)
  decision = rank(e.type) vs current status/timestamp
  if applicable: repo.casStatus(...) else markIgnored(reason)
  audit
```
## 7. Reconciliation and sweeper
`StuckMessageSweeper.sweep()` (`@Scheduled`): PROCESSING with `locked_until < now` and SENT_TO_PROVIDER older than `receipt-timeout` => DELIVERY_UNKNOWN via CAS. `ReconciliationService.reconcile()` handles DELIVERY_UNKNOWN older than `reconcile-after`.

## 8. Provider invocation
`ResilientProviderInvoker.send` wraps `CircuitBreaker` + `TimeLimiter` on `providerExecutor` (bounded `ThreadPoolExecutor`, `AbortPolicy` mapped to RATE_LIMITED/TRANSIENT). Exceptions map to `ProviderOutcome` (timeouts => TIMEOUT; connection refused before send => TRANSIENT_FAILURE; unknown/after-send errors => AMBIGUOUS).

## 9. Concurrency table
| Thread pool | Size | Bound/Queue | Rejection |
|---|---|---|---|
| Tomcat | server config | accept queue | 503 |
| Dispatch workers | `dispatch.concurrency` | prefetch | backpressure |
| Provider executor | >= workers | small queue | map to retry |
| Scheduler | small fixed | none | skip tick |
| Batch | `bulk.concurrency` | chunk | job slows |

## 10. Error mapping
`GlobalExceptionHandler`: `ApiException` subclasses to `ApiError` + status; `IllegalStateTransitionException` => 409; optimistic lock => 409 `CONFLICT_OPTIMISTIC_LOCK`; unexpected => 500 `INTERNAL_ERROR` with correlation id only.
