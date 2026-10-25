# Question Bank 1: Java, JVM, Concurrency, Spring Boot, Hibernate, Testing (Q01-Q24)

This is a reference-implementation study bank. Platform facts cite the design contract (CONTRACT section X / ADR-000N). Nothing here claims that any code path was executed.

## JVM, Memory and Concurrency

### Q01. How do you choose between G1 and ZGC for a latency-sensitive Spring Boot service, and which flags would you set first?
**Tags:** JVM/GC, Senior, Concept
**Analogy:** G1 is a janitor who cleans the busiest rooms first in short shifts; ZGC is a cleaning crew that works while the guests are still in the building, so nobody is asked to leave.
**Model answer:**
G1 is the Java 21 default. It splits the heap into regions, collects the regions with the most garbage first, and aims for a pause target (`-XX:MaxGCPauseMillis`, default 200 ms). It balances throughput and latency and is the right default for most REST and consumer workloads. ZGC (generational mode in Java 21 via `-XX:+UseZGC -XX:+ZGenerational`) does almost all work concurrently, keeps pauses well under a few milliseconds regardless of heap size, and costs some throughput and memory headroom.

My decision process:
- Measure first: enable GC logging (`-Xlog:gc*:file=gc.log:time,uptime,level,tags`) and look at pause p99 against the SLO. If p99 request latency is dominated by GC pauses over 100 ms, consider ZGC.
- Heap under roughly 4 GB with a modest allocation rate: G1 is fine and simpler.
- Large heaps (tens of GB), strict tail latency, or allocation spikes: ZGC.
- Containers: use `-XX:MaxRAMPercentage=70` instead of hard `-Xmx` so the heap tracks the cgroup limit, leaving room for metaspace, thread stacks, direct buffers and code cache.

First flags I would set: `-XX:MaxRAMPercentage`, `-XX:+ExitOnOutOfMemoryError` (let the orchestrator restart), `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/dumps`, GC logging, and `-XX:+AlwaysPreTouch` only for stable long-lived services. I would not hand-tune region sizes or young-gen ratios until a profile shows a need.

For this platform, the consumer path is allocation-light (envelopes carry no content, CONTRACT section 6), so G1 defaults are the starting point and ZGC is an experiment gated by measured pause data.
**Follow-ups:**
- What is a humongous allocation in G1 and why does it hurt?
- How would you prove that a latency spike is GC and not a downstream call?
- What does generational ZGC change versus single-generation ZGC?
**Pitfalls:**
- Choosing ZGC "because it is newer" without measurements.
- Setting `-Xmx` equal to the container limit, causing OOMKilled by the kernel.
- Tuning many flags blindly instead of reading GC logs.

### Q02. A pod is repeatedly OOMKilled but heap dashboards show plenty of free heap. How do you investigate?
**Tags:** JVM/GC, Senior, Scenario
**Analogy:** The fridge (heap) is half empty, but the whole kitchen (container) is full because of the pots, chairs and boxes on the floor (threads, metaspace, native buffers).
**Model answer:**
Clarify first: is it the Linux OOM killer (exit code 137, `OOMKilled` in `kubectl describe`) or a Java `OutOfMemoryError`? The first means total process memory exceeded the cgroup limit, not that the heap is full.

Approach:
- Account for the whole footprint: heap + metaspace + thread stacks (about 1 MB each, so 500 platform threads is about 500 MB) + direct/NIO buffers (Netty, Lettuce, Rabbit client) + code cache + GC native structures + glibc arenas.
- Turn on Native Memory Tracking in a test environment: `-XX:NativeMemoryTracking=summary`, then `jcmd <pid> VM.native_memory summary` and diff over time.
- Check thread count growth (`jcmd <pid> Thread.print`, metric `jvm_threads_live_threads`). An unbounded executor or a thread-per-request client under slow provider latency is a classic cause.
- Check direct memory: `-XX:MaxDirectMemorySize` and the `jvm_buffer_memory_used_bytes` metric.
- Compare `-Xmx` or `MaxRAMPercentage` to the limit. A heap at 90 percent of the limit leaves no room.

Fixes: lower `MaxRAMPercentage` to 60-70, bound pools (the platform uses a bounded `ThreadPoolExecutor` for consumers, CONTRACT section 1), set `MALLOC_ARENA_MAX=2` where glibc fragmentation appears, cap direct memory, and raise the limit only after understanding growth.

What I would measure: RSS versus heap used, thread count, direct buffer bytes, restart frequency, and whether growth correlates with provider latency or queue depth.
**Follow-ups:**
- How do you tell metaspace leaks from heap leaks?
- Why can a Java process exceed `-Xmx`?
- What would you change in the Deployment manifest besides JVM flags?
**Pitfalls:**
- Raising the memory limit without finding the cause.
- Only looking at heap graphs.
- Ignoring thread stack memory.

### Q03. How do you size the thread pool that consumes messages and calls an email provider?
**Tags:** Concurrency, Intermediate, Concept
**Analogy:** A restaurant with a fixed number of waiters: too few and orders wait, too many and they bump into each other and the kitchen cannot keep up.
**Model answer:**
Start from Little's Law: concurrency = throughput x latency. If the target is 200 sends per second and provider p95 latency is 250 ms, you need about 200 x 0.25 = 50 in-flight calls. Because the work is I/O bound, threads spend most of the time waiting, so the pool can exceed the core count; a CPU-bound pool would be about cores + 1.

Practical rules:
- Use a bounded `ThreadPoolExecutor` with a bounded queue and an explicit rejection policy. Unbounded queues hide overload and turn it into memory growth and timeouts. The platform defaults to 8 workers and a prefetch of 16 (`messaging.dispatch.concurrency`, `prefetch`, CONTRACT sections 6 and 9).
- Pair the pool with a per-call timeout (Resilience4j time limiter) so a slow provider cannot hold every thread forever.
- Use a bulkhead per provider so one slow provider does not consume the shared pool.
- Prefetch controls how much work is pulled from the broker; it should be around concurrency times a small factor, otherwise one slow consumer hoards messages.
- Scale out instances rather than only threads when the DB connection pool (HikariCP) becomes the cap: threads beyond pool size just queue for a connection.

Validate with a load test: increase concurrency until throughput stops rising or p99 grows; pick a value below the knee. Track `messaging.queue.inflight`, executor active/queue gauges and `hikaricp_connections_pending`.

<!-- in-project:b12 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/config/AsyncConfig.java`
>
> Fixed pool (core = max = `dispatch.concurrency`, default 8) and a bounded queue equal to the prefetch (default 16). `CallerRunsPolicy` slows the consumer loop when the pool is saturated, which is natural backpressure.
>
> ```java
> @Bean(name = "dispatchExecutor")
> public ThreadPoolTaskExecutor dispatchExecutor(MessagingProperties props) {
>     int n = Math.max(1, props.dispatch().concurrency());
>     ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
>     ex.setThreadNamePrefix("dispatch-");
>     ex.setCorePoolSize(n);
>     ex.setMaxPoolSize(n);
>     ex.setQueueCapacity(Math.max(1, props.dispatch().prefetch()));
>     ex.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
>     ex.setWaitForTasksToCompleteOnShutdown(true);
>     ex.setAwaitTerminationSeconds(30);
>     return ex;
> }
> ```

**Follow-ups:**
- What happens if the pool size exceeds the Hikari pool size?
- What rejection policy would you choose and why?
- How does this change with virtual threads?
**Pitfalls:**
- Using `Executors.newCachedThreadPool()` or an unbounded queue.
- Sizing by core count for I/O-bound work.
- Forgetting downstream rate limits.

### Q04. What are virtual threads in Java 21, when would you use them in a messaging platform, and what is pinning?
**Tags:** Concurrency, Senior, Concept
**Analogy:** Platform threads are a few expensive taxi drivers; virtual threads are many cheap passengers who get off the taxi while they wait for a coffee and get back on afterwards.
**Model answer:**
Virtual threads are JVM-scheduled threads multiplexed over a small pool of carrier (platform) threads. When a virtual thread blocks on supported I/O (sockets, `Thread.sleep`, `java.util.concurrent` locks) it is unmounted, freeing the carrier. This lets you write simple blocking code at very high concurrency (tens of thousands of concurrent waits) without reactive complexity. In Spring Boot 3.2+ you enable them with `spring.threads.virtual.enabled=true`.

Good fits: request-per-thread REST handlers calling slow downstreams, and consumers waiting on provider HTTP calls. Poor fits: CPU-bound work (no benefit), and code that holds scarce resources per thread.

Key caveats:
- Pinning: in Java 21 a virtual thread blocked inside a `synchronized` block or a native frame stays pinned to its carrier, which can starve the carrier pool. Prefer `ReentrantLock`; diagnose with `-Djdk.tracePinnedThreads=full` or JFR `jdk.VirtualThreadPinned`.
- Virtual threads remove the thread-count cap, so you must add explicit limits elsewhere: a `Semaphore` or bulkhead for provider concurrency, the Hikari pool size, and rate limiters. Otherwise ten thousand concurrent calls hammer a provider.
- Do not pool virtual threads; create one per task.
- `ThreadLocal` usage and MDC need care for memory and propagation.

For this platform, the bounded consumer pool is a deliberate backpressure device (CONTRACT section 6). With virtual threads I would keep the concurrency limit using a semaphore, not drop it.
**Follow-ups:**
- How do you detect carrier-thread starvation?
- Does JDBC with virtual threads remove the need for connection pools?
- What changed regarding pinning in later JDKs?
**Pitfalls:**
- Saying "virtual threads make everything faster".
- Removing all concurrency limits.
- Pooling virtual threads.

### Q05. Consumers stop making progress; the queue grows and all worker threads are WAITING inside provider calls. What happened and what do you do?
**Tags:** Concurrency, Senior, Scenario
**Analogy:** Every cashier is stuck waiting for one customer's card machine, so the line outside gets longer even though nobody is "broken".
**Model answer:**
Clarify: which provider, since when, and is the provider timing out or merely slow? Check `messaging.provider.latency`, `messaging.provider.calls{outcome}` and `messaging.queue.depth`.

Likely cause: a provider became slow, calls had no effective timeout or a very long one, and every worker thread is occupied. Throughput collapses to workers divided by latency.

Immediate actions:
- Confirm with `jstack` or `jcmd Thread.print`: many workers in `SocketInputStream.read` or the HTTP client's `await`.
- Open the circuit manually or disable the provider (`PUT /api/v1/providers/{id}/config`, CONTRACT section 5) so traffic fails fast to retry or fallback.
- Lower the provider rate limit so retries stop compounding.

Structural fixes:
- Per-call timeout via the Resilience4j time limiter, shorter than the thread pool's tolerance (CONTRACT section 1).
- Circuit breaker so open state fails in microseconds and frees threads.
- Bulkhead per provider so email trouble does not starve SMS and push.
- Backoff with jitter on retries to avoid retry storms.

Failure modes to watch: ambiguous outcomes. Timeouts become `DELIVERY_UNKNOWN` and are reconciled, not blindly retried (CONTRACT section 6). Measure: time to detect (alert on p95 provider latency and in-flight saturation), time to mitigate, and queue age (oldest message).
**Follow-ups:**
- How should timeout, retry count and circuit-breaker thresholds relate?
- What is the difference between a bulkhead and a rate limiter?
- How do you drain the backlog safely after recovery?
**Pitfalls:**
- Restarting the pods and hoping.
- Increasing thread count (more threads stuck).
- Retrying immediately on timeout.

### Q06. Explain check-then-act race conditions with a concrete example from a message-processing service and how to remove them.
**Tags:** Concurrency, Intermediate, Concept
**Analogy:** Two people look at the last slice of pizza, both see it, both reach for it; only one can actually eat it.
**Model answer:**
A check-then-act race happens when code reads state, decides, then writes, and another thread or instance changes the state between the read and the write. Example:

```java
// BROKEN: two consumers can both pass the check
MessageRequest m = repo.findById(id).orElseThrow();
if (m.getStatus() == QUEUED) {
    m.setStatus(PROCESSING);   // both threads get here
    repo.save(m);
    provider.send(...);        // duplicate send
}
```

Removal options, from cheapest to heaviest:
- Atomic compare-and-set in SQL: `UPDATE message_request SET status='PROCESSING', locked_until=? WHERE id=? AND status='QUEUED'`; the winner sees `rowsUpdated == 1`, the loser acks and skips. This is exactly the dispatch claim in CONTRACT section 6.
- Optimistic locking with a `@Version` column, retrying or discarding on `OptimisticLockException`.
- A unique constraint as a lock substitute (for example `processed_message.dispatch_id UNIQUE`); insert-first, handle the duplicate-key exception.
- Pessimistic `SELECT ... FOR UPDATE [SKIP LOCKED]` for work-queue style claims.
- In memory only: `ConcurrentHashMap.computeIfAbsent`, `AtomicReference.compareAndSet`, never `containsKey` then `put`.

Key insight: in a multi-instance service, JVM-level `synchronized` is useless; the database (or Redis with Lua) is the arbiter. Test with a concurrent test that starts N threads on a `CountDownLatch` and asserts exactly one winner.
**Follow-ups:**
- How does `SKIP LOCKED` help multiple pollers?
- Why is a unique constraint often better than a lock?
- How do you unit test a race deterministically?
**Pitfalls:**
- Using `synchronized` across instances.
- Relying on read-then-write inside `@Transactional` at READ COMMITTED.
- Assuming a 200 ms window is "unlikely".

### Q07. What do volatile, synchronized and atomics guarantee, and what is happens-before?
**Tags:** Concurrency, Senior, Concept
**Analogy:** Happens-before is a promise that when you read the shared whiteboard, you see everything written before the other person "signed" it.
**Model answer:**
The Java Memory Model defines when writes by one thread are visible to reads in another. Without a happens-before edge, the compiler and CPU may reorder or cache, so a thread can observe stale or partially published values.

- `volatile`: guarantees visibility and ordering for that variable (a write happens-before subsequent reads) but not atomicity of compound actions. `count++` on a volatile is still a race.
- `synchronized` / `ReentrantLock`: mutual exclusion plus visibility; unlock happens-before the next lock of the same monitor.
- `AtomicInteger`, `AtomicReference`, `LongAdder`: lock-free atomic read-modify-write using CAS. `LongAdder` beats `AtomicLong` under heavy write contention (counters, metrics).
- `final` fields: safely published after construction if `this` does not escape.
- Other edges: `Thread.start`, `join`, `ExecutorService.submit` to task, `Future.get`, concurrent collection operations.

Practical guidance:
- Prefer immutable objects (records) and message passing; share mutable state only through well-understood classes.
- Safe publication: `static` initializer, `volatile`, or `final`; double-checked locking works only with `volatile`.
- Do not put compound invariants across multiple volatiles; use one lock or one immutable snapshot swapped via `AtomicReference`.

Platform example: provider fault-mode or circuit state readable by many threads suits an immutable record in a `volatile`/`AtomicReference` field.
**Follow-ups:**
- Why is double-checked locking broken without volatile?
- When would you choose `LongAdder` over `AtomicLong`?
- What does `ConcurrentHashMap.merge` guarantee?
**Pitfalls:**
- Believing volatile makes `i++` thread-safe.
- Synchronizing on a mutable or boxed object.
- Calling alien methods while holding a lock.

### Q08. How do you use CompletableFuture safely for calling providers in parallel, including timeouts and exception handling?
**Tags:** Concurrency, Intermediate, Concept
**Analogy:** Ordering from three shops at once and setting an alarm; whichever has not arrived when the alarm rings, you stop waiting for.
**Model answer:**
`CompletableFuture` composes asynchronous steps, but the defaults are dangerous:
- `supplyAsync(task)` without an executor uses `ForkJoinPool.commonPool()`, shared by the whole JVM and sized by cores. Blocking I/O there starves everything. Always pass a dedicated, bounded executor.
- Timeouts: use `orTimeout(500, MILLISECONDS)` or `completeOnTimeout(default, ...)` (Java 9+). Note that `orTimeout` completes the future exceptionally but does not interrupt the underlying task; cancellation of the real HTTP call must be handled by the client's own timeout.
- Exceptions are wrapped in `CompletionException`; unwrap with `exceptionally`, `handle` or `whenComplete`. An unobserved failed future is silently lost, so always attach a handler.
- Joining many: `allOf` returns `Void`; collect results with `thenApply` on the originals. Beware `join()` inside a pool thread of the same pool (deadlock-by-starvation).
- Context propagation: MDC, security context and tracing context do not cross threads automatically; wrap executors (Spring `TaskDecorator`, Micrometer context-propagation).

Example for a fallback-channel race (not used for consent-sensitive sends, because fallback must go through policy sequentially, CONTRACT section 3.3):

```java
CompletableFuture.supplyAsync(() -> lookup(id), ioExecutor)
    .orTimeout(300, TimeUnit.MILLISECONDS)
    .exceptionally(ex -> ProviderStatus.unknown());
```

For simple blocking code in Java 21, a virtual-thread executor plus structured code is often clearer than chained futures.
**Follow-ups:**
- Difference between `thenApply` and `thenCompose`?
- What happens to the task on `orTimeout`?
- How do you propagate MDC correlation id?
**Pitfalls:**
- Using the common pool for blocking calls.
- Swallowing exceptions.
- Calling `get()` with no timeout.

### Q09. Production threads are hung and CPU is idle. How do you find and fix a deadlock?
**Tags:** Concurrency, Senior, Scenario
**Analogy:** Two people each hold one chopstick and refuse to put it down until they get the other one.
**Model answer:**
Clarify: is it a JVM-level lock deadlock, or threads waiting on database locks, a connection pool, or a downstream? Idle CPU plus stuck requests points to waiting, not computing.

Approach:
1. Capture 3 thread dumps 10 seconds apart (`jcmd <pid> Thread.print` or `jstack -l`). The JVM reports "Found one Java-level deadlock" with the lock cycle for monitors and `ReentrantLock` ownership (`-l`).
2. If no Java deadlock appears, classify waiting threads: most in `HikariPool.getConnection` means pool exhaustion (often a thread holding a connection while calling another service or waiting for a second connection: a nested `REQUIRES_NEW` pool-starvation deadlock); most in socket read means downstream.
3. Check DB: `pg_locks`/`pg_stat_activity` (Postgres) or `v$lock` / `v$session` (Oracle) for lock waits and database-detected deadlocks (error 40P01 or ORA-00060).

Typical fixes:
- Impose a global lock ordering (always lock lower id first).
- Shorten critical sections; never call external services while holding a lock or a transaction.
- Use `tryLock` with timeout and back off.
- Avoid nested transactions that need a second connection; size the pool above the worst-case nesting or restructure.
- For DB deadlocks: consistent update order, smaller transactions, retry on deadlock with jitter.

Measure: `hikaricp_connections_pending`, thread state counts, lock-wait time. Add a regression test with two threads acquiring in opposite order and a timeout assertion, and an alert on pending connections above zero for more than 30 seconds.
**Follow-ups:**
- What is livelock and how does it differ?
- How do you reproduce a deadlock in a test?
- Why can `REQUIRES_NEW` cause pool deadlock?
**Pitfalls:**
- Restarting without capturing dumps.
- Taking one dump only.
- Blaming the database before reading the dump.

## Spring Core, Transactions and Hibernate

### Q10. Explain dependency injection in Spring Boot: constructor versus field injection, bean scopes, and how circular dependencies are handled.
**Tags:** Spring, Foundational, Concept
**Analogy:** A restaurant kitchen where the manager hands each chef the tools they need before the shift starts, instead of each chef hunting for tools.
**Model answer:**
The container instantiates beans, resolves their dependencies and wires them. Prefer constructor injection:
- Dependencies are `final`, objects are never half-built, and unit tests can construct them without Spring.
- Missing dependencies fail at startup, not at first use.
- With a single constructor, `@Autowired` is unnecessary.

Field injection hides dependencies, blocks immutability and complicates tests; setter injection is for optional dependencies.

Scopes: `singleton` (default, one per context, must be thread-safe and stateless), `prototype` (new instance per lookup), `request`/`session` (web). Never keep per-request state in a singleton field. Inject a prototype or request bean into a singleton via `ObjectProvider`/`@Lookup` or a scoped proxy, otherwise you get one instance forever.

Circular dependencies: constructor-based cycles fail; Spring Boot 2.6+ forbids cycles by default (`spring.main.allow-circular-references=false`). The right fix is design: extract a third component, invert via events or an interface, or use `@Lazy` as a last resort. In the platform layout, `service/` depends on `repository/`, `provider/` and `messaging/` ports, never the reverse (CONTRACT section 2).

Also know: profiles and `@ConditionalOnProperty` choose adapters (memory versus Rabbit bus, CONTRACT section 9), `@ConfigurationProperties` records give typed validated config, and `@Primary`/`@Qualifier` disambiguate multiple implementations such as providers.
**Follow-ups:**
- How would you inject all `EmailProvider` implementations?
- What is the difference between `@Component` and `@Bean`?
- How does `@ConfigurationProperties` validation work?
**Pitfalls:**
- Field injection with `@Autowired` everywhere.
- Mutable state in singletons.
- Solving cycles with `@Lazy` blindly.

### Q11. Why does `@Transactional` sometimes not work, and how do proxies explain it?
**Tags:** Spring, Intermediate, Concept
**Analogy:** The doorman (proxy) checks you in only if you come through the front door; if you walk through the inside corridor from another room, he never sees you.
**Model answer:**
Spring implements `@Transactional` with AOP proxies. A caller invokes the proxy, which begins the transaction, calls the target and commits or rolls back. Failure cases:
- Self-invocation: `this.otherMethod()` bypasses the proxy, so annotations on `otherMethod` are ignored. Fix: move the method to another bean, inject the bean into itself carefully, or use programmatic `TransactionTemplate`.
- Visibility: proxies only apply to `public` methods (and class must not be `final` with CGLIB).
- Exceptions: by default only unchecked exceptions and `Error` roll back. Checked exceptions need `rollbackFor`. Swallowing the exception inside the method prevents rollback.
- Wrong thread: new threads do not share the transaction (`@Async`, executors, `CompletableFuture`).
- Wrong bean: a bean created with `new` is not proxied.
- Read-only misuse: `readOnly=true` is a hint (flush mode manual for Hibernate), not a guarantee.
- `@Transactional` on a private `@PostConstruct` or during startup before the proxy is ready.

Propagation basics: `REQUIRED` (default, join or create), `REQUIRES_NEW` (suspend and create; uses a second connection), `MANDATORY`, `NEVER`, `NOT_SUPPORTED`, `NESTED` (savepoint, JDBC only).

Keep transactions short, never include remote calls, and put the boundary on the service layer. In the platform, the submission transaction writes `message_request`, `outbox_event` and audit in one transaction (CONTRACT section 6), which is why `NotificationService.submit` owns the boundary, not the controller or repository.

<!-- in-project:b01 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/service/IdempotencyService.java`
>
> The annotation is on a public method of a Spring bean called from another bean, so the proxy applies; MANDATORY makes a call without an active transaction fail loudly instead of silently running without one.
>
> ```java
> /** Joins the caller's transaction: the record flips to COMPLETED atomically with the business change. */
> @Transactional(propagation = Propagation.MANDATORY)
> public void complete(String tenantId, String key, String claimId, String requestHash, int status, String body,
>         String messageId) {
>     Instant expires = clock.instant().plus(props.idempotency().recordTtl());
>     repository.complete(claimId, status, body, messageId, expires);
>     TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
>         @Override
>         public void afterCommit() {
>             writeCache(tenantId, key, new Cached(requestHash, status, body, messageId), expires);
>         }
>     });
> }
> ```

**Follow-ups:**
- How do you verify a transaction is active in a test?
- What does `rollbackFor` change?
- When is `TransactionTemplate` better?
**Pitfalls:**
- Annotating private methods.
- Catching and logging exceptions inside the transactional method.
- Calling HTTP providers inside a transaction.

### Q12. A message row was committed but the queue publish failed (or the reverse). How do you make these two actions consistent?
**Tags:** Spring, Senior, Scenario
**Analogy:** Writing a letter and dropping it in the postbox are two steps; the outbox is a tray on your desk so a forgotten letter is always found and sent later.
**Model answer:**
Clarify the requirement: is losing a message acceptable (no for transactional alerts), and is a duplicate acceptable (yes, consumers are idempotent)? That points to at-least-once.

The dual-write problem: a DB commit and a broker publish are two systems and cannot be atomic without distributed transactions. Failure modes:
- Publish first, DB fails: consumers see a message for data that does not exist (ghost message).
- Commit first, publish fails or process dies: committed work is never dispatched (lost message).

Solution: the transactional outbox (ADR on outbox, CONTRACT section 6). In one local DB transaction write `message_request` (VALIDATED), `outbox_event` (NEW) and audit. A separate `OutboxPublisher` scheduled every 200 ms claims batches by compare-and-set, publishes to `MessageBus`, then marks events SENT and moves the message to QUEUED. If the publisher crashes between publish and mark, the event is published again; the consumer's `processed_message` dedupe and CAS QUEUED to PROCESSING absorb duplicates.

Alternatives: `@TransactionalEventListener(AFTER_COMMIT)` narrows but does not close the gap (crash after commit). CDC (Debezium) tails the outbox table to avoid polling.

Measure: outbox lag (oldest NEW event age), publish failure rate, duplicate-consumption rate. Retention job prunes SENT rows. Trade-off: added latency of up to the poll interval and extra DB writes in exchange for no lost messages.

<!-- in-project:b03 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/scheduler/OutboxPublisher.java`
>
> The row claim, the VALIDATED to QUEUED transition and the broker publish share one transaction: a failed publish rolls everything back, which gives at-least-once delivery (DEVIATIONS stage 2, item 3).
>
> ```java
> boolean publishOne(String outboxId) {
>     return Boolean.TRUE.equals(tx.execute(status -> {
>         if (outbox.markSent(outboxId, clock.instant()) == 0) {
>             return false; // another instance won the claim
>         }
>         OutboxEvent row = outbox.findById(outboxId).orElse(null);
>         // ... parse envelope, VALIDATED -> QUEUED via notifications.transition(...)
>         bus.publish(envelope); // BusRejectedException rolls the claim back
>         return true;
>     }));
> }
> ```

**Follow-ups:**
- How do multiple publisher instances avoid double publishing?
- How would CDC change the design?
- What ordering guarantees does the outbox give?
**Pitfalls:**
- Publishing inside the transaction and calling it atomic.
- Claiming exactly-once.
- Not pruning the outbox table.

### Q13. When is `REQUIRES_NEW` appropriate, and what are the risks?
**Tags:** Spring, Senior, Concept
**Analogy:** Starting a second, separate notebook for audit notes so they survive even if you tear up the first notebook.
**Model answer:**
`REQUIRES_NEW` suspends the current transaction and opens an independent one on a second connection. It commits or rolls back independently.

Legitimate uses:
- Audit or attempt records that must persist even when the main business transaction rolls back (for example recording a `message_attempt` row as `STARTED` before a provider call, CONTRACT section 6).
- Idempotency claims that must be visible to concurrent requests immediately (claim IN_PROGRESS, commit, then proceed).
- Failure recording after an exception.

Risks:
- Connection pool deadlock: the outer transaction holds connection A and waits for connection B; with N concurrent requests and a pool of N, all hold A and nobody gets B. Size the pool for worst-case nesting or avoid nesting.
- Lost atomicity: the inner commit remains if the outer rolls back; data may become inconsistent with the outer outcome. That is intentional for audit and dangerous for business state.
- Visibility: the inner transaction cannot see uncommitted outer changes (and may deadlock waiting on rows the outer transaction locked).
- Self-invocation: it only works across a proxy boundary.

Better patterns: write audit in the same transaction when it should vanish with a rollback, or write it after the outer transaction via an event; use the outbox for cross-effect consistency. Test with concurrency equal to the pool size to expose starvation.

<!-- in-project:b02 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/service/IdempotencyService.java`
>
> The claim must commit on its own so a concurrent duplicate sees it, and a unique-constraint violation (not a check-then-insert) decides the race. The javadoc says `begin` "must be called outside the business transaction".
>
> ```java
> this.requiresNew = new TransactionTemplate(tm);
> this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
> // ...
> private String tryClaim(String tenantId, String key, String hash, Instant now, Instant expires) {
>     try {
>         return requiresNew.execute(s -> {
>             IdempotencyRecord r = new IdempotencyRecord();
>             r.setId(Ids.newId());
>             r.setTenantId(tenantId);
>             r.setIdemKey(key);
>             r.setRequestHash(hash);
>             r.setStatus(IdempotencyRecord.IN_PROGRESS);
>             repository.saveAndFlush(r);
>             return r.getId();
>         });
>     } catch (DataIntegrityViolationException dup) {
>         return null;
>     }
> }
> ```

**Follow-ups:**
- How does `NESTED` differ?
- How would you detect pool starvation in production?
- How would you order the writes to avoid row-lock deadlock?
**Pitfalls:**
- Using `REQUIRES_NEW` as a default "to be safe".
- Ignoring the second connection.
- Expecting inner changes to roll back.

### Q14. A list endpoint takes 4 seconds and the SQL log shows hundreds of queries. How do you diagnose and fix the N+1 problem?
**Tags:** Hibernate, Intermediate, Scenario
**Analogy:** Asking the librarian for one book, then walking back for each author's biography one at a time instead of requesting all of them in a single trip.
**Model answer:**
Clarify: which endpoint, how many rows per page, how many associations are touched while mapping to the DTO? Typical culprit: `GET /api/v1/messages` mapping entities where `recipient`, `attempts` or `deliveryEvents` are lazily loaded per row.

Diagnose: enable `hibernate.generate_statistics` or log statements (`org.hibernate.SQL`, never in prod permanently), use a test with a query-count assertion (datasource-proxy or Hibernate `Statistics`), and check `http_server_requests_seconds` against DB time.

Fixes, in order of preference:
- Projections: select only DTO fields via interface/class-based projections or `@Query("select new ...")`. The list view does not need full entities.
- `@EntityGraph` or `join fetch` for to-one associations (recipient).
- Collections: avoid join-fetching collections with pagination (Hibernate paginates in memory, warning HHH000104). Instead page ids first, then load children with `where id in (:ids)`, or use `@BatchSize` / `hibernate.default_batch_fetch_size=50`.
- Do not "fix" with `FetchType.EAGER` globally; it creates N+1 everywhere.
- Add the index that supports the filter and sort (`(tenant_id,status,created_at DESC)` per CONTRACT section 4).

The platform contract requires repositories without N+1 (CONTRACT section 2). I would add a regression test that asserts the query count for a page of 50 is under a fixed number.

What I would measure: queries per request, DB time share, p95 latency before and after. Also verify the cartesian-product risk: joining two collections multiplies rows.
**Follow-ups:**
- Why is a collection join fetch with `Pageable` a problem?
- When is `@BatchSize` preferable to `join fetch`?
- How do you prevent regressions?
**Pitfalls:**
- Switching everything to EAGER.
- Fetching two bags in one query (`MultipleBagFetchException`).
- Returning entities directly from controllers.

### Q15. Two operators edit the same recipient's preferences simultaneously and one change silently disappears. How do you prevent lost updates?
**Tags:** Hibernate, Intermediate, Scenario
**Analogy:** Two people editing the same shared document offline; when the second saves, the first person's changes vanish unless the system notices the document changed.
**Model answer:**
Clarify: how frequent are conflicting writes, and what does the user experience need? For preferences (human edits, low contention, correctness matters because opt-outs have legal weight), optimistic locking is right.

Approach:
- Add a `version` column and `@Version` on `recipient_preference` (marked with a diamond in CONTRACT section 4). Hibernate adds `WHERE id=? AND version=?` to updates; zero rows updated raises `OptimisticLockException`.
- Return the version in `GET /recipients/{id}/preferences`; require it on `PUT` (CONTRACT section 5). A stale write yields HTTP 409 `CONFLICT_OPTIMISTIC_LOCK`, and the UI shows a conflict dialog with a reload/merge choice.
- Use `If-Match` with the version as an ETag for generic REST semantics.

Failure modes: retrying blindly on a user-driven write would overwrite the other edit; only retry automatically for system-driven idempotent updates. Opt-out writes should be merged conservatively: an unsubscribe should never be lost, so consider a separate append-only suppression path (a dedicated endpoint, CONTRACT section 5) rather than a read-modify-write of the whole row.

Pessimistic locking (`PESSIMISTIC_WRITE`) is for high contention and short critical sections such as work claims, not for human think-time edits because it would hold locks across user sessions (which is not even possible across HTTP calls).

Measure: count of 409s, frequency of retries, and any support tickets about missing edits.

<!-- in-project:b21 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/service/PreferenceService.java`
>
> The entity has `@Version private Long version;` and the API requires the client to send it back. A stale version becomes `409 CONFLICT_OPTIMISTIC_LOCK` ("reload and retry"), which the Angular console turns into a conflict dialog.
>
> ```java
> RecipientPreference p = preferences.findById(recipientId).orElse(null);
> if (p == null) {
>     if (req.version() != null) {
>         throw optimistic();
>     }
>     // ... create
> } else if (req.version() == null || !req.version().equals(p.getVersion())) {
>     // stale or missing token: the client must re-read before writing
>     throw optimistic();
> }
> ```

**Follow-ups:**
- Why not use pessimistic locking here?
- How do you handle the conflict in the Angular UI?
- How would you merge field-level changes?
**Pitfalls:**
- Last-write-wins by default.
- Auto-retrying user edits.
- Holding DB locks across HTTP requests.

### Q16. What causes `LazyInitializationException`, and what is wrong with Open Session In View?
**Tags:** Hibernate, Intermediate, Concept
**Analogy:** A lazy association is a pizza coupon: it is only valid at the shop (open session); outside, you hold a coupon you cannot redeem.
**Model answer:**
Lazy associations load via a proxy that needs an open Hibernate session. Once the transaction or session closes (service returns, controller later touches `message.getAttempts()`), accessing the proxy throws `LazyInitializationException`.

Spring Boot enables Open Session In View (`spring.jpa.open-in-view=true`) by default, keeping the session open through view rendering. It hides the exception but:
- Holds a DB connection for the entire request including JSON serialization and slow clients, hurting pool capacity.
- Encourages lazy loading from controllers and serializers, creating N+1 outside any transaction boundary and unpredictable query counts.
- Blurs layer boundaries.

Recommended approach: set `spring.jpa.open-in-view=false`; load exactly what the use case needs inside the service transaction (projection, `@EntityGraph`, fetch join) and map to DTO records before returning. Controllers return DTOs, never entities (the platform's web layer uses request/response records, CONTRACT section 2). Use `Hibernate.initialize` only sparingly.

Also avoid exposing entities through Jackson: bidirectional associations produce infinite recursion and leak fields (such as full addresses, violating the "masked addresses only" rule in CONTRACT section 5).

Measure: connection hold time (`hikaricp_connections_usage_seconds`) and queries per request before and after turning OSIV off.
**Follow-ups:**
- How do you load a collection for a single detail endpoint?
- What replaces lazy loading in a DTO-based design?
- What are the risks of `@Transactional` on controllers?
**Pitfalls:**
- Setting everything EAGER.
- Leaving OSIV on and calling it fine.
- Returning entities with bidirectional links.

### Q17. How do you make bulk inserts fast with JPA/Hibernate, and why does the id generation strategy matter?
**Tags:** Hibernate, Senior, Concept
**Analogy:** Mailing 500 letters in one trip to the post office instead of 500 separate trips.
**Model answer:**
Hibernate can batch JDBC statements (`hibernate.jdbc.batch_size=50`, `hibernate.order_inserts=true`, `hibernate.order_updates=true`) so many inserts travel in one round trip. Limits:
- `GenerationType.IDENTITY` disables insert batching because Hibernate must execute each insert immediately to learn the generated key. The platform uses UUID string ids (CONTRACT section 4), which are assigned in application code and batch fine.
- With sequence ids use `@SequenceGenerator(allocationSize=50)` aligned with the batch size, so one sequence call serves 50 inserts (relevant for Oracle, where sequences are standard).
- The persistence context grows with every managed entity; for large loads, `flush()` and `clear()` per chunk (Spring Batch chunk-size 500 commits per chunk anyway, CONTRACT section 7).
- Use `StatelessSession` or plain `JdbcTemplate.batchUpdate` for very large inserts where entity state is unnecessary.
- PostgreSQL JDBC needs `reWriteBatchedInserts=true` for true multi-row rewriting; Oracle benefits from batched statements with array binding.

Also consider: indexes slow inserts (keep only what queries need), disable `show_sql`, avoid `save()` on entities with assigned ids (it triggers a `select` merge check; implement `Persistable` or use a version field to signal new).

Measure rows per second at chunk 100, 500, 1000 and watch for diminishing returns, transaction duration and lock contention. Choose the chunk size by measurement, not intuition.

<!-- in-project:b39 -->
> [!NOTE] In this project
> **File:** `backend/src/main/resources/application.yml`
>
> Ids are application-generated (`Ids.newId()`), so there is no sequence round trip per insert and JDBC batching can work. The bulk path additionally commits one chunk per transaction.
>
> ```yaml
> spring:
>   jpa:
>     properties:
>       hibernate:
>         jdbc:
>           time_zone: UTC
>           batch_size: 50
>         order_inserts: true
>         order_updates: true
> ```

**Follow-ups:**
- Why does `save()` issue a select for assigned ids?
- How do batch size and chunk size interact?
- How would you handle duplicate keys in a batch?
**Pitfalls:**
- Using IDENTITY and expecting batching.
- Never clearing the persistence context.
- Huge transactions that bloat undo/WAL.

## Exceptions, Testing, Configuration and Design

### Q18. Design the exception handling strategy for a REST service: which exceptions map to which responses, and where do you log?
**Tags:** Spring, Intermediate, Concept
**Analogy:** A hospital triage desk: every problem gets one clear label and one routing, rather than each doctor inventing a response.
**Model answer:**
Centralize in a `@RestControllerAdvice` (`web/GlobalExceptionHandler`, CONTRACT section 2) that maps exceptions to the `ApiError` payload `{timestamp,status,error,code,message,path,correlationId,details}` (CONTRACT section 5).

Hierarchy: a small `ApiException` base with subclasses carrying an `ErrorCode` and HTTP status (not found, conflict, illegal state, rate limited). Domain code throws domain exceptions; only the web layer knows HTTP.

Mapping examples:
- Bean validation (`MethodArgumentNotValidException`) to 400 `VALIDATION_FAILED` with per-field `details`.
- Malformed JSON to 400 `MALFORMED_REQUEST`.
- Missing resource or another tenant's resource to 404 (do not leak existence).
- `IllegalStateTransitionException` to 409 `ILLEGAL_STATE_TRANSITION`.
- Optimistic lock failure to 409 `CONFLICT_OPTIMISTIC_LOCK`.
- Idempotency key reuse to 422, in-flight duplicate to 409.
- Unknown exceptions to 500 `INTERNAL_ERROR` with a generic message and the correlation id, never the stack trace.

Logging rules: log once, at the boundary that handles the exception. 4xx at INFO/WARN without stack trace, 5xx at ERROR with stack trace and MDC `correlationId`, `tenant`, `messageId`. Never log secrets or full PII (`common/Redaction`).

Also handle asynchronous paths separately: consumers cannot return HTTP; they classify outcomes (transient, permanent, poison) and move messages to retry or DLQ rather than throwing indefinitely.

Test the handler with `@WebMvcTest` and assert the JSON shape and codes.

<!-- in-project:b20 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/web/GlobalExceptionHandler.java`
>
> One `@RestControllerAdvice`-style handler class maps every exception to the `ApiError` shape. Note that only the exception class name is logged, never the message, which could contain SQL parameters or customer data.
>
> ```java
> @ExceptionHandler({org.springframework.dao.DataAccessResourceFailureException.class,
>         org.springframework.transaction.CannotCreateTransactionException.class,
>         org.springframework.dao.TransientDataAccessException.class})
> public ResponseEntity<ApiError> dependencyUnavailable(Exception e, HttpServletRequest req) {
>     log.warn("Database dependency unavailable on {} {}: {}", req.getMethod(), req.getRequestURI(),
>             e.getClass().getSimpleName());
>     HttpHeaders h = new HttpHeaders();
>     h.set(HttpHeaders.RETRY_AFTER, "5");
>     return respond(req, HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE,
>             "A required dependency is temporarily unavailable; retry with the same idempotency key", null, h);
> }
> ```

**Follow-ups:**
- Why return 404 instead of 403 for another tenant's resource?
- How do you avoid logging the same exception three times?
- How do you handle exceptions in `@Async` code?
**Pitfalls:**
- Returning stack traces to clients.
- Using 200 with an error body.
- Catch-and-ignore blocks.

### Q19. What does a sensible test strategy look like for a Spring Boot messaging service?
**Tags:** Testing, Intermediate, Concept
**Analogy:** Checking each Lego brick, then each assembled section, and only occasionally the full castle, because the full castle is slow to check.
**Model answer:**
Use a pyramid with fast feedback at the base:
- Pure unit tests (JUnit 5, Mockito/AssertJ): domain logic with no Spring context. The `MessageStateMachine` transition table, retry/backoff calculation, `TemplateRenderer`, GSM-7 segment counting. Parameterized tests cover every cell of the transition table including illegal ones.
- Slice tests: `@WebMvcTest` (controllers, validation, error JSON, security rules with `@WithMockUser`/JWT post-processors), `@DataJpaTest` (repositories, query count, Flyway migration on H2 in PostgreSQL mode, CONTRACT section 1).
- Integration tests: `@SpringBootTest` with profile `test`, in-memory bus and coordination so they are deterministic (CONTRACT sections 2 and 9). Cover end-to-end flows such as accept, dispatch, callback, delivered.
- Container tests (`*ContainerIT` with Testcontainers) for real PostgreSQL, RabbitMQ, Redis and Mongo; in the contract they are opt-in via a Maven profile and may not be runnable everywhere, so report them as not executed unless run.
- Contract tests: a test asserts the springdoc output contains every committed OpenAPI path (CONTRACT section 1).
- Concurrency/failure tests: two threads claiming the same message, duplicate callbacks, simulated provider faults via `FaultInjector`.
- Performance smoke: a JVM load harness; record numbers only if executed.

Avoid mocking what you do not own (mock the `EmailProvider` port, not the HTTP library internals). Prefer fakes (in-memory bus) over deep mock stubbing. Keep tests independent: unique H2 database names per context.

Measure: coverage on critical packages (state machine, policy, retry), mutation score if available, test runtime, flake rate.
**Follow-ups:**
- What is the difference between `@MockBean` and a fake?
- How do you test security rules?
- When are Testcontainers worth the cost?
**Pitfalls:**
- Only end-to-end tests.
- Over-mocking so tests mirror implementation.
- Asserting on log output.

### Q20. An integration test that checks an async consumer passes locally but fails randomly in CI. How do you stabilize it?
**Tags:** Testing, Intermediate, Scenario
**Analogy:** Checking whether the pizza has arrived after exactly 30 seconds, instead of checking the doorbell until it rings or you give up.
**Model answer:**
Clarify: what does the test assert, and how does it wait? Random CI-only failures nearly always mean a timing assumption (`Thread.sleep(500)`), shared state between tests, or resource contention on slower runners.

Root causes to check:
- Fixed sleeps: replace with polling for a condition with a timeout, for example Awaitility `await().atMost(5, SECONDS).untilAsserted(...)`.
- Shared state: a static in-memory bus or a shared H2 database between contexts. Use unique database names per context (CONTRACT section 2) and clear state or use a fresh bus per test.
- Nondeterminism: random provider faults. The `test` profile should be deterministic with fault injection off unless a test sets a specific mode.
- Time: inject a `Clock` bean (CONTRACT section 2) and advance it instead of waiting for real backoff or quiet-hours windows.
- Thread leaks: consumers from a prior context still running; stop lifecycle beans in `@AfterEach`/context close.
- Asynchrony in assertions: assert final state, not intermediate transitions that may be skipped by fast processing.

For the retry scenario, set backoff base to a few milliseconds in test config, or call the retry scheduler directly. For ordering-sensitive logic, test the pure function without the bus.

Process: reproduce by running the test in a loop under CPU limit (for example `-Dsurefire.rerunFailingTestsCount` only as diagnostics, not as a fix), then fix the cause. Quarantining flaky tests is acceptable only with an owner and a ticket.

Measure: flake rate per test over 50 runs.
**Follow-ups:**
- Why is retrying failed tests a smell?
- How do you test code that depends on `Instant.now()`?
- How do you ensure consumers stop at context shutdown?
**Pitfalls:**
- Increasing the sleep.
- Disabling the test.
- Relying on test execution order.

### Q21. How does Spring Boot configuration and profile handling work, and how do you keep secrets out of config files?
**Tags:** Spring, Intermediate, Concept
**Analogy:** A settings panel with layers: factory defaults, then your changes, then the manager's override sticker on top.
**Model answer:**
Spring Boot resolves properties from layered sources with a defined precedence: defaults in `application.yml`, profile files (`application-local.yml`), OS environment variables, JVM system properties, and command-line args (highest among common ones). The relaxed binding maps `MESSAGING_DISPATCH_CONCURRENCY` to `messaging.dispatch.concurrency`.

Profiles select adapters and settings: `local` and `test` (H2, in-memory bus and coordination, dev token) versus a prod-like profile (PostgreSQL, Rabbit, Redis, Mongo, issuer-uri, no dev endpoints) (CONTRACT section 9). Use `@Profile` and `@ConditionalOnProperty` so dev-only beans (`DevTokenController`) do not exist outside permitted profiles, a security control and not a convenience.

Typed config: `@ConfigurationProperties` records with `@Validated` fail startup on bad values. Group keys under `messaging.*`.

Secrets: never in files or images. Provide them as environment variables injected from a secret manager (Kubernetes Secret, CF user-provided service or credential hub, Vault), or mounted files via `spring.config.import=optional:configtree:/etc/secrets/`. Config examples contain only dummy placeholders (CONTRACT section 1 mentions dummy callback secrets). Rotate keys by key id (`X-Provider-Key-Id`) so two keys are valid during rotation.

Operational points: log effective config at startup with secrets masked, use Actuator `configprops` and `env` endpoints only behind authentication (and `management.endpoint.env.show-values=never`), and fail fast when a required secret is missing in a prod profile.
**Follow-ups:**
- What is the precedence between env vars and `application.yml`?
- How would you rotate a webhook signing secret with zero downtime?
- Why is `@Value` less preferable than `@ConfigurationProperties`?
**Pitfalls:**
- Committing `.env` files with real values.
- Leaving actuator `env` open.
- Enabling dev endpoints through a profile typo.

### Q22. Heap usage climbs steadily over days until GC thrashes. How do you find a memory leak?
**Tags:** JVM/GC, Senior, Scenario
**Analogy:** A bathtub where the tap drips faster than the drain; nothing looks wrong until the tub overflows days later.
**Model answer:**
Clarify: does the heap fall back after full GCs (sawtooth with a rising floor) or is it simply a large working set? The signal of a leak is a rising post-GC baseline (`jvm_memory_used_bytes` after GC, or `jvm_gc_live_data_size_bytes`).

Steps:
1. Confirm the trend over multiple days, correlate with deploys, traffic, specific endpoints, or provider failures.
2. Capture a heap dump at a high baseline (`jcmd <pid> GC.heap_dump`, or automatically via `-XX:+HeapDumpOnOutOfMemoryError`). Treat dumps as sensitive: they contain PII and secrets, so store them encrypted with restricted access.
3. Analyze in Eclipse MAT or VisualVM: dominator tree, "leak suspects", GC roots path for the largest retained sets. Alternatively take two dumps and compare histograms (`jcmd GC.class_histogram`).
4. Use JFR allocation profiling (`jdk.ObjectAllocationSample`) to see who allocates.

Common leak sources in a messaging service: unbounded caches or maps keyed by message id (replay or idempotency caches without TTL/size limit; use Caffeine with `maximumSize` and `expireAfterWrite`), listeners never deregistered, `ThreadLocal` values on pool threads, unbounded in-memory queues during backpressure, static collections, metrics with high-cardinality tags (a tag per `messageId` or per `jobId` forever, which balloons Micrometer registries), and sessions or JDBC resources not closed.

Fix, add a regression guard (bounded structures, a soak test watching post-GC heap) and alert on baseline growth rate. Trade-off: heap dumps pause the JVM; take them from one instance after draining traffic.
**Follow-ups:**
- Why are high-cardinality metric tags a memory problem?
- How do you differentiate a leak from a cache that is too big?
- How do you take a heap dump safely in Kubernetes?
**Pitfalls:**
- Increasing `-Xmx` as the fix.
- Sharing heap dumps casually.
- Forgetting metaspace and native leaks.

### Q23. How would you model the message lifecycle state machine in Java 21 so that illegal transitions are impossible to miss?
**Tags:** Java, Senior, Concept
**Analogy:** A board game with a rule card: you may only move your piece along the arrows printed on the board.
**Model answer:**
The lifecycle in CONTRACT section 3.1 is a finite state machine with a fixed transition table and terminal states. I would encode it in pure Java with no framework dependency (`domain/` package, CONTRACT section 2):

```java
public enum MessageStatus {
    ACCEPTED, VALIDATED, QUEUED, PROCESSING, SENT_TO_PROVIDER, DELIVERED,
    REJECTED, RETRY_PENDING, FAILED, DEAD_LETTERED, CANCELLED, DELIVERY_UNKNOWN;
}

public final class MessageStateMachine {
    private static final Map<MessageStatus, Set<MessageStatus>> ALLOWED = Map.of(
        MessageStatus.ACCEPTED, EnumSet.of(MessageStatus.VALIDATED, MessageStatus.REJECTED)
        /* ... remaining rows from the contract table ... */);
    public static void require(MessageStatus from, MessageStatus to) {
        if (!ALLOWED.getOrDefault(from, Set.of()).contains(to))
            throw new IllegalStateTransitionException(from, to);
    }
}
```

Design points:
- Single source of truth: one table, used by API handlers and consumers; a parameterized test iterates all 12 x 12 pairs and compares against the contract.
- Database enforcement of the race: perform the transition with `UPDATE ... WHERE id=? AND status=:from`; the state machine validates legality, the CAS validates currency.
- Sealed interfaces and records fit for event types and outcomes (`ProviderOutcome` mapped with an exhaustive `switch` expression, so adding an enum value breaks compilation until handled).
- Distinguish API-driven illegal transitions (HTTP 409) from event-driven ones (log and audit), per the contract.
- Ranking for callbacks (monotonic rank, CONTRACT section 3.4) is separate from legality: it decides which delivery event may overwrite which status.

Every transition emits a `messaging.transitions{from,to}` counter and an audit event.

<!-- in-project:b05 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/domain/MessageStateMachine.java`
>
> A pure, table-driven class (an `EnumMap` of `EnumSet`s) with `assertTransition` throwing `IllegalStateTransitionException`. It is an enum table rather than sealed types because the status is persisted as a string column and changed by a compare-and-set UPDATE.
>
> ```java
> static {
>     TABLE.put(ACCEPTED, EnumSet.of(VALIDATED, REJECTED));
>     TABLE.put(VALIDATED, EnumSet.of(QUEUED, CANCELLED, REJECTED));
>     TABLE.put(QUEUED, EnumSet.of(PROCESSING, CANCELLED));
>     TABLE.put(PROCESSING, EnumSet.of(SENT_TO_PROVIDER, RETRY_PENDING, FAILED, DELIVERY_UNKNOWN, REJECTED));
>     TABLE.put(RETRY_PENDING, EnumSet.of(QUEUED, DEAD_LETTERED, CANCELLED));
>     TABLE.put(SENT_TO_PROVIDER, EnumSet.of(DELIVERED, FAILED, DELIVERY_UNKNOWN));
>     TABLE.put(DELIVERY_UNKNOWN, EnumSet.of(DELIVERED, FAILED));
>     TABLE.put(FAILED, EnumSet.of(DEAD_LETTERED, QUEUED));
>     TABLE.put(DEAD_LETTERED, EnumSet.of(QUEUED));
>     TABLE.put(DELIVERED, EnumSet.noneOf(MessageStatus.class));
>     TABLE.put(REJECTED, EnumSet.noneOf(MessageStatus.class));
>     TABLE.put(CANCELLED, EnumSet.noneOf(MessageStatus.class));
> }
> ```

**Follow-ups:**
- Why not use Spring State Machine?
- How would you add a new state without breaking running messages?
- How do you test exhaustiveness?
**Pitfalls:**
- Scattering `if (status == ...)` checks.
- Allowing any-to-any updates through a generic setter.
- Forgetting the DB-level compare-and-set.

### Q24. The scheduled outbox poller runs on three instances and messages get published twice. How do you fix it without adding a distributed lock library?
**Tags:** Concurrency, Senior, Scenario
**Analogy:** Three clerks pulling tickets from the same pile: give each ticket a "taken by" stamp that can only be applied once, so no two clerks work the same ticket.
**Model answer:**
Clarify the requirement: duplicates must be harmless (consumers are idempotent), but we still want to avoid wasted work and thundering duplicates, and we want to scale the poller horizontally rather than elect a single leader.

`@Scheduled` fires on every instance. Options:
- Leader election or a lock library (ShedLock, Redis lock): simple but serializes work and needs lock expiry tuning. The contract chooses not to use ShedLock (CONTRACT section 4).
- Row-level claim with compare-and-set: each instance claims a batch with an atomic statement, for example `UPDATE outbox_event SET status='CLAIMED', claimed_by=?, claimed_at=? WHERE id IN (select id ... WHERE status='NEW' ORDER BY created_at LIMIT 100) AND status='NEW'`. Only rows whose update succeeded belong to this instance. On PostgreSQL and Oracle, `SELECT ... FOR UPDATE SKIP LOCKED` is the more efficient variant; the portable CAS UPDATE also works on H2 in PostgreSQL mode (CONTRACT section 1 forbids PG-only syntax).
- Lease expiry: a `claimed_at` older than a timeout is reclaimable if an instance crashed after claiming.

Failure modes: publisher crashes after publish but before marking SENT leads to a duplicate publish. That is acceptable because the consumer dedupes with `processed_message` and CAS QUEUED to PROCESSING (CONTRACT section 6). The same pattern applies to `ScheduledDispatchPoller` and `StuckMessageSweeper` (CONTRACT section 2).

Measure: outbox lag, claim conflicts, duplicate-publish count, rows per batch. Tune batch size and interval against DB load.

<!-- in-project:b04 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/repository/OutboxEventRepository.java`
>
> Three instances may all select the same due rows, but only the one whose UPDATE changes a row (`== 1`) proceeds; the others see `0` and skip. No ShedLock or leader election is needed.
>
> ```java
> @Modifying(clearAutomatically = true, flushAutomatically = true)
> @Transactional
> @Query("update OutboxEvent o set o.status = 'SENT', o.sentAt = :now where o.id = :id and o.status = 'NEW'")
> int markSent(@Param("id") String id, @Param("now") Instant now);
> ```

**Follow-ups:**
- What does `SKIP LOCKED` do and which databases support it?
- How would you order messages by priority when claiming?
- What if the clocks on instances disagree?
**Pitfalls:**
- Assuming `@Scheduled` runs once per cluster.
- Using in-memory flags as locks.
- Claim without lease expiry (stuck forever).
