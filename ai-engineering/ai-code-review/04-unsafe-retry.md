# 4. Unsafe Retry

Invariants: 5, 6, 4 (timeouts), idempotency of provider call.

## Flawed code
```java
ProviderResult r;
int attempts = 0;
while (true) {                                  // retry loop inside the consumer thread
    try { r = smsProvider.send(req); break; }
    catch (Exception e) {                       // includes read timeout after the vendor accepted
        if (++attempts > 10) throw e;
        Thread.sleep(100);                      // fixed delay, holds the worker, no jitter
    }
}
```
## Why it is wrong
- Retrying after a read timeout can send a duplicate SMS (the vendor may have accepted).
- Tight fixed-delay loop amplifies provider outage; blocks a worker thread (starvation); no attempt accounting, no state, lost on crash.
- Catches everything (permanent failures retried).

## Corrected version
```java
ProviderResult r = invoker.send(provider, req, idempotencyRef);   // timeout + breaker, no loop
switch (r.outcome()) {
  case ACCEPTED -> transition(m, SENT_TO_PROVIDER);
  case TRANSIENT_FAILURE, RATE_LIMITED -> scheduleRetry(m, retryPolicy.nextDelay(attemptNo)); // RETRY_PENDING, jittered backoff
  case PERMANENT_FAILURE -> transition(m, FAILED);
  case TIMEOUT, AMBIGUOUS -> m.channel() == PUSH ? scheduleRetry(m, retryPolicy.nextDelay(attemptNo))
                                                 : transition(m, DELIVERY_UNKNOWN);   // reconcile, no blind resend
}
```
## Rationale
Classification decides retry safety; retry state is persisted (`next_attempt_at`) and spread by jitter; ambiguity is resolved by evidence (ADR-0006, ADR-0010).

## Regression tests
- `FailureRecoveryIT#scenario04_providerTimeout_movesToDeliveryUnknown_reconcilesWithoutDuplicateSend` (provider call count == 1).
- `RetryPolicyTest#delayWithinJitterBounds_forEachAttempt`, `#capRespected`.
- `DispatchServiceTest#permanentFailure_isNotRetried`.
