# Java, JVM, Garbage Collection and Concurrency

This chapter is a reference for senior Java interviews, tied to the messaging reference implementation (Java 21, Spring Boot 3.5.16; CONTRACT section 1). Nothing here claims that a benchmark or tool was run; flag values and commands are standard practice, not measured results from this project.

## Kid-friendly analogies for the hardest ideas

| Concept | Analogy |
|---|---|
| Heap, young and old generation | A school cafeteria. Most trays (short-lived objects) are used once and cleared right away at the "young" table. The few trays someone keeps all semester move to the "old" shelf, which is cleaned far less often. |
| Garbage collection pause | The lunch lady shouts "everybody freeze" while she wipes tables. G1 freezes briefly and often. ZGC wipes tables while kids keep eating, so the freezes are tiny. |
| Thread pool | A restaurant with 8 cooks. Orders (tasks) wait on a ticket rail (queue). Hiring a new cook per order would crowd the kitchen. |
| Virtual threads | Instead of 8 cooks standing idle waiting for the oven, each cook can put a dish in the oven, walk away, and start another dish. Thousands of dishes can be "in progress" with a few cooks. |
| Pinning | A cook who is chained to the oven door (a `synchronized` block) cannot walk away while waiting, so that cook is stuck even though the idea was to be free. |
| Happens-before (JMM) | A rule that says "if I write a note and then ring the bell, anyone who hears the bell is guaranteed to see my note." Without the bell, they may read an old copy of the note. |
| Deadlock | Two kids each holding one crayon and each waiting for the other's crayon. Nobody can finish the drawing. |

## JVM memory areas

| Area | Holds | Failure signature |
|---|---|---|
| Heap (young: eden plus survivors; old) | Objects | `OutOfMemoryError: Java heap space`, long GC pauses |
| Metaspace (native memory) | Class metadata | `OutOfMemoryError: Metaspace` (classloader leaks, heavy dynamic proxies) |
| Thread stacks | Frames, locals, one per platform thread | `StackOverflowError`, or "unable to create native thread" |
| Code cache | JIT-compiled code | Compilation stops, throughput drops |
| Direct buffers and native memory | NIO buffers, Netty, JNI | RSS grows beyond `-Xmx` |
| GC and runtime overhead | Card tables, remembered sets | Process RSS is larger than heap |

A senior point worth saying aloud: container memory limit is not equal to `-Xmx`. Budget heap plus metaspace plus thread stacks plus direct memory plus overhead. Prefer `-XX:MaxRAMPercentage=70` style sizing in containers over a hard-coded `-Xmx` so the heap follows the cgroup limit, and leave headroom.

### Object lifecycle and escape analysis

Objects are allocated in eden using thread-local allocation buffers (TLABs), so allocation is a pointer bump. Survivors are copied between survivor spaces and promoted after surviving a threshold number of collections. The JIT can use escape analysis to scalar-replace objects that never leave a method, so tiny short-lived objects are often free. Do not micro-optimise allocation without a profile.

## Garbage collectors

### G1 (default on server-class machines)

G1 divides the heap into equal regions (1 to 32 MB). It does mostly-concurrent marking, and evacuates live objects in stop-the-world "mixed" collections, choosing regions with the most garbage first. It aims for a pause target.

| Flag | Meaning |
|---|---|
| `-XX:+UseG1GC` | Select G1 (already default in most cases) |
| `-XX:MaxGCPauseMillis=200` | Soft pause goal; lowering it trades throughput for shorter pauses |
| `-XX:InitiatingHeapOccupancyPercent=45` | When concurrent marking starts |
| `-XX:G1HeapRegionSize` | Region size; matters for humongous objects (half a region or larger) |
| `-XX:+AlwaysPreTouch` | Touch heap at start; predictable latency, slower startup |

Common G1 problems: humongous allocations (large arrays, big JSON strings) causing fragmentation and early marking; to-space exhaustion when promotion outruns reclamation, leading to a full GC; very large remembered sets. Remedy: stream rather than buffer large payloads (relevant for the bulk CSV reader in CONTRACT section 7, which is streamed and never fully loaded), and look at GC logs before changing flags.

### ZGC (generational in Java 21)

ZGC does almost all work concurrently using colored pointers and load barriers, with pauses that stay in the sub-millisecond to low-millisecond range regardless of heap size. Java 21 introduced a generational mode.

```text
-XX:+UseZGC -XX:+ZGenerational
```

Tradeoffs: slightly higher CPU and memory overhead than G1, and it needs headroom (allocation faster than reclamation causes allocation stalls). Choose ZGC when tail latency matters and heaps are large; choose G1 for balanced defaults. Parallel GC suits batch throughput where pauses are irrelevant. For a messaging platform, most heaps are small and G1 is a defensible default; mention ZGC as an option when p99 dispatch latency is dominated by pauses and the GC logs prove it.

### Logging and basic flags

```text
-Xlog:gc*,safepoint:file=gc.log:time,uptime,level,tags:filecount=5,filesize=20m
-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/dumps
-XX:+ExitOnOutOfMemoryError
-XX:NativeMemoryTracking=summary
```

`ExitOnOutOfMemoryError` is often the right choice under an orchestrator: a JVM in a degraded OOM state is worse than a restart. Heap dumps may contain personal data; treat them as sensitive and restrict access.

## Reading a GC problem

| Symptom | Likely cause | Check |
|---|---|---|
| Frequent young GCs, healthy old gen | High allocation rate | Allocation profile in JFR |
| Old gen climbs steadily, full GCs reclaim little | Leak (unbounded cache, listener, ThreadLocal) | Heap dump, dominator tree |
| Long pauses with low heap use | Safepoint issues, swapping, CPU throttling in container | `safepoint` logs, cgroup throttling |
| Allocation stalls on ZGC | Heap too small for allocation rate | Increase heap or reduce churn |

## Thread pools

`ThreadPoolExecutor` parameters: core size, max size, queue, keep-alive, rejection handler. The rule people forget: threads beyond core are only created when the queue is full. An unbounded queue means max size is never used and memory is the limit.

```java
ThreadPoolExecutor pool = new ThreadPoolExecutor(
    8, 8,
    0L, TimeUnit.MILLISECONDS,
    new ArrayBlockingQueue<>(16),
    new ThreadPoolExecutor.CallerRunsPolicy());
```

CONTRACT section 1 and section 6 call for a bounded `ThreadPoolExecutor` for consumers, with `consumer.concurrency` default 8 and `consumer.prefetch` default 16. The reasoning interviewers want:

- Bounded queue gives backpressure. `CallerRunsPolicy` slows the producer, `AbortPolicy` throws, a custom handler can return 503 with `Retry-After` (CONTRACT section 6, backpressure).
- Size by workload. CPU-bound: about the number of cores. I/O-bound: cores multiplied by (1 plus wait time over compute time), then validated under load. Provider calls are I/O-bound, but the real ceiling is the provider rate limit, so more threads beyond that only create queueing downstream.
- Name threads (a `ThreadFactory`) so `jstack` output is readable.
- Shut down gracefully: stop intake, `awaitTermination`, then `shutdownNow`. CONTRACT section 6 specifies a 30 s graceful shutdown where unacked messages return to the queue.
- Propagate context deliberately. MDC (`correlationId`, `tenant`, `messageId`, CONTRACT section 1) is thread-local and does not cross pools unless you copy it in a task decorator.

<!-- in-project:d07 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/provider/ResilientProviderInvoker.java`
>
> Three bounded pools exist: `dispatch-*` (consumers, CallerRuns), `provider-call-*` (provider calls with time limit, Abort) and `bulk-chunk-*` / `bulk-job-*` (batch, Abort, a full launch queue makes job creation answer 503). Named threads make `jstack` readable.
>
> ```java
> int threads = Math.max(1, cfg.executorThreads());
> this.executor = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
>         new ArrayBlockingQueue<>(Math.max(1, cfg.executorQueue())), r -> {
>             Thread t = new Thread(r, "provider-call-" + n.incrementAndGet());
>             t.setDaemon(true);
>             return t;
>         }, new ThreadPoolExecutor.AbortPolicy());
> this.executor.allowCoreThreadTimeOut(true);
> ```


## CompletableFuture

```java
CompletableFuture<ProviderResult> f =
    CompletableFuture.supplyAsync(() -> provider.send(req), ioExecutor)
        .orTimeout(2, TimeUnit.SECONDS)
        .exceptionally(ex -> ProviderResult.timeout());
```

Points to make:

- Always pass an explicit executor. The default `ForkJoinPool.commonPool()` is shared and sized for CPU work; blocking calls there starve unrelated code.
- `thenApply` runs on whichever thread completes the previous stage; `thenApplyAsync` hops to a pool. Know which you want.
- `orTimeout` and `completeOnTimeout` (Java 9+) bound waiting, but a timeout does not cancel the underlying work. `cancel(true)` on a `CompletableFuture` does not interrupt the running thread either. This matters for the ambiguous-outcome case: the provider may have accepted the request even though your future timed out (CONTRACT section 6, TIMEOUT maps to DELIVERY_UNKNOWN).
- Combine with `allOf`, `anyOf`, `thenCombine`. Handle exceptions with `handle` or `exceptionally`; unhandled failures are silently held in the future.

## Virtual threads (Java 21)

Virtual threads are cheap, JVM-scheduled threads multiplexed on a small pool of carrier (platform) threads. When a virtual thread blocks on supported I/O or `java.util.concurrent` locks, it unmounts and the carrier runs another one.

```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    futures = requests.stream()
        .map(r -> executor.submit(() -> callProvider(r)))
        .toList();
}
```

In Spring Boot 3.2 and later, `spring.threads.virtual.enabled=true` runs request handling and `@Async` tasks on virtual threads. Guidance:

| Do | Do not |
|---|---|
| Use one virtual thread per task; never pool them | Wrap virtual threads in a fixed pool "to limit them" |
| Limit concurrency with a `Semaphore` where a downstream is scarce | Assume unlimited threads means unlimited database or provider capacity |
| Keep code simple and blocking | Use them for CPU-bound loops (no benefit) |
| Prefer `ReentrantLock` around blocking sections | Hold `synchronized` across blocking I/O |

### Pinning

A virtual thread is pinned to its carrier when it blocks inside a `synchronized` block or method, or while a native frame is on the stack. Pinned blocking holds the carrier, reducing throughput and in the worst case exhausting carriers. In Java 21 this is a real limitation (it was relaxed for `synchronized` in later releases, so verify against your exact JDK). Diagnose with:

```text
-Djdk.tracePinnedThreads=full
```

or JFR event `jdk.VirtualThreadPinned`. Fix by replacing `synchronized` with `ReentrantLock` where the guarded region blocks, and by checking third-party drivers.

Other cautions: `ThreadLocal` works but per-task copies at massive scale are costly; connection pools (HikariCP) still bound database concurrency, so threads will queue on the pool, and that is correct behaviour. In this design the consumer concurrency limit (CONTRACT section 6) exists to protect the provider and database, so virtual threads would be adopted behind that limit, not instead of it.

## Java Memory Model basics

The JMM defines when one thread's writes are guaranteed visible to another. Without synchronisation, the compiler and CPU may reorder or cache.

| Mechanism | Guarantee |
|---|---|
| `volatile` | Visibility and ordering for that variable; not atomicity of compound actions (`count++`) |
| `synchronized` / `Lock` | Mutual exclusion plus happens-before between unlock and subsequent lock |
| `final` fields | Safe publication after constructor completes (if `this` does not escape) |
| `Thread.start()` / `join()` | Happens-before edges |
| `java.util.concurrent` (queues, `ConcurrentHashMap`, atomics) | Built-in happens-before on hand-off |

```java
class Flag {
    private volatile boolean stop;          // visible across threads
    void run() { while (!stop) { work(); } }
    void halt() { stop = true; }
}
```

Without `volatile`, the loop may never see the update. Classic broken pattern: double-checked locking without `volatile` can expose a partially constructed object.

Atomics (`AtomicLong`, `LongAdder`) use CAS. `LongAdder` outperforms `AtomicLong` under heavy contention for counters. This is the same compare-and-set idea the contract uses at database level for row claims (CONTRACT section 4, the scheduling note on `UPDATE ... WHERE status=...`).

## Locks and coordination

| Tool | Use |
|---|---|
| `synchronized` | Simple, short critical sections |
| `ReentrantLock` | Try-lock, timed lock, interruptible, fairness, virtual-thread friendly |
| `ReadWriteLock` / `StampedLock` | Read-heavy shared state |
| `Semaphore` | Bound concurrent access (for example provider calls) |
| `CountDownLatch`, `CyclicBarrier`, `Phaser` | Coordination |
| `ConcurrentHashMap.compute*` | Atomic per-key updates |

Deadlock requires four conditions: mutual exclusion, hold and wait, no preemption, circular wait. Break circular wait by always acquiring locks in a global order, or use `tryLock` with a timeout. Prefer immutability and message passing (a queue between threads) over shared mutable state; the `MessageBus` abstraction (CONTRACT section 2) is that idea at system scale.

Rate limiting inside the JVM can be a token bucket; CONTRACT section 1 places the distributed version in Redis (Lua script) with an in-memory adapter for local and test runs.

## Diagnosing with jstack and JFR

### jstack and thread dumps

```text
jcmd <pid> Thread.print -l
jstack -l <pid>
jcmd <pid> Thread.dump_to_file -format=json threads.json
```

The JSON form is useful in Java 21 because it includes virtual threads, which the classic dump omits. Take three dumps a few seconds apart; threads that stay in the same frame are the story.

| Thread state | Meaning | Typical interpretation |
|---|---|---|
| `RUNNABLE` | On CPU or in native I/O | A socket read can show as RUNNABLE while idle |
| `BLOCKED` | Waiting for a monitor | Lock contention, look for the owner |
| `WAITING` / `TIMED_WAITING` | Parked, sleeping, waiting on a condition | Pool starvation if all workers wait on a queue or connection pool |

`jstack` reports "Found one Java-level deadlock" with the lock cycle. For a stalled consumer, look for all worker threads waiting on `HikariPool.getConnection` (pool exhausted) or on a provider socket (slow downstream with no timeout).

### JFR

```text
jcmd <pid> JFR.start name=diag settings=profile duration=120s filename=diag.jfr
jfr print --events jdk.GCPhasePause diag.jfr
jfr summary diag.jfr
```

JFR has low overhead and can run continuously. Useful events: `jdk.ObjectAllocationSample`, `jdk.GarbageCollection`, `jdk.JavaMonitorEnter`, `jdk.ThreadPark`, `jdk.VirtualThreadPinned`, `jdk.ExecutionSample`. Open the recording in JDK Mission Control, or script with the `jfr` tool.

### Diagnostic playbook

1. Is it CPU, memory, I/O or lock contention? Look at the metrics first (JVM and HikariCP metrics in CONTRACT section 8).
2. Take thread dumps and group by identical stacks.
3. Check GC logs for pause time and frequency.
4. Capture a short JFR recording and look at hot methods and lock waits.
5. Form a hypothesis, change one thing, re-measure.

## Worked example: slow dispatch consumer

Symptom: `messaging.dispatch.delay` (CONTRACT section 8) rises while queue depth grows. Thread dumps show 8 of 8 consumer threads in `TIMED_WAITING` inside provider socket reads; the circuit breaker is closed. The reasoning chain: the provider is slow but under the failure-rate threshold, and the time limiter is longer than the typical latency. Options: lower the time limit, add a slow-call-rate threshold to the circuit breaker, and not simply raise concurrency, which would multiply load on a provider that is already struggling. This keeps the consumer bounded and fails fast into the retry path.

## Key takeaways

- Size containers for heap plus metaspace plus stacks plus direct memory; prefer percentage-based heap sizing.
- G1 is the balanced default; ZGC (generational in Java 21) targets very low pauses at some CPU and memory cost; always read GC logs before tuning.
- A `ThreadPoolExecutor` only grows past core size when the queue is full; bounded queues provide backpressure.
- Use explicit executors with `CompletableFuture`; a timeout does not cancel the work, which matters for ambiguous provider outcomes.
- Virtual threads help blocking I/O concurrency but do not remove downstream limits; avoid blocking inside `synchronized` in Java 21 because of pinning, and detect it with `jdk.tracePinnedThreads` or JFR.
- `volatile` gives visibility, not atomicity; locks give both plus happens-before; prefer `java.util.concurrent` types.
- Diagnose with repeated thread dumps (including the JSON dump for virtual threads) and short JFR recordings, and change one variable at a time.
