# 7. Unbounded Concurrency

Invariants: 4 (bounded pools and queues, timeouts).

## Flawed code
```java
private final ExecutorService executor = Executors.newCachedThreadPool();   // unbounded threads

public void onMessage(DispatchEnvelope env) {
    executor.submit(() -> dispatchService.process(env));    // consumer acks immediately, work is unbounded
    ack();
}
// bulk
rows.parallelStream().forEach(r -> notificationService.submitInternal(r));   // common pool, unbounded DB pressure
```
## Why it is wrong
- Acking before processing loses messages on crash; unbounded threads under a slow provider: memory/thread exhaustion; DB pool starvation; no backpressure to the broker (prefetch is meaningless).
- `parallelStream` uses the shared ForkJoin pool and ignores rate limits and chunk transactions.

## Corrected version
```java
ThreadPoolExecutor workers = new ThreadPoolExecutor(
    cfg.concurrency(), cfg.concurrency(), 0L, TimeUnit.MILLISECONDS,
    new ArrayBlockingQueue<>(cfg.prefetch()),
    namedThreads("dispatch-worker-"),
    new ThreadPoolExecutor.AbortPolicy());                       // rejection => nack/requeue with delay, never drop

public void onMessage(DispatchEnvelope env, Ack ack) {
    try { workers.execute(() -> { process(env); ack.ack(); }); }  // ack after the outcome is persisted
    catch (RejectedExecutionException e) { ack.nackRequeueDelayed(env); metrics.rejected(); }
}
```
Bulk uses the Spring Batch chunk step with `bulk.concurrency` TaskExecutor and the writer-side `RateLimiter`.

## Rationale
Bounded resources convert overload into backpressure (queue depth, 503 at API) instead of cascading failure.

## Regression tests
- `DispatchConsumerTest#slowProvider_threadCountStaysBounded` (assert `Thread.getAllStackTraces` for `dispatch-worker-*` <= concurrency).
- `FailureRecoveryIT#scenario10_providerRateLimitAndBackpressure_noAttemptConsumed_apiReturns503`.
- `DispatchConsumerTest#crashBeforeAck_messageRedelivered`.
