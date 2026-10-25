# Question Bank 4: Spring Batch, SQL, Oracle, PostgreSQL and MongoDB (Q71-Q94)

Reference-implementation study material. Database facts for this platform: PostgreSQL 16 primary with H2 in PostgreSQL mode for local/test, Oracle reference DDL and mapping documented separately, MongoDB only for diagnostics (CONTRACT sections 1 and 4). Real Oracle, PostgreSQL and MongoDB servers were not exercised by this document.

## Spring Batch

### Q71. Explain Spring Batch's core model: Job, Step, JobInstance, JobExecution, StepExecution and the JobRepository.
**Tags:** Spring Batch, Foundational, Concept
**Analogy:** A job is a recipe, a job instance is "today's cake", each execution is one attempt at baking it, and the job repository is the kitchen notebook recording how far each attempt got.
**Model answer:**
- `Job`: a named flow of `Step`s. A step is either chunk-oriented (reader, processor, writer) or a tasklet (single action).
- `JobParameters`: the identifying inputs (for example file name and a run date). A `JobInstance` is a Job plus identifying parameters: one logical run. Same job and same parameters means same instance, which can only complete once.
- `JobExecution`: one physical attempt to run a `JobInstance`; a failed instance can be restarted, creating a new execution. `StepExecution`: the attempt of one step, holding counters (read, write, skip, commit counts) and the `ExecutionContext`.
- `ExecutionContext`: key-value state persisted at each chunk commit; readers store their position (for example line number) so a restart resumes from the last committed chunk.
- `JobRepository`: persists all this metadata in relational tables (`BATCH_JOB_INSTANCE`, `BATCH_JOB_EXECUTION`, `BATCH_STEP_EXECUTION`, `BATCH_*_CONTEXT`, with Spring Batch 5 schema scripts). It also enforces that the same instance is not run concurrently. The platform uses the JDBC job repository in the same database, with schema in migration `V100__spring_batch.sql` (CONTRACT sections 1 and 4).
- `JobLauncher` starts jobs; `JobExplorer` reads history; `JobOperator` supports stop, restart, abandon.

Why it matters: restartability, auditability and operator control come from this metadata. Spring Batch 5 changes: no `@EnableBatchProcessing` auto-config subtleties (defining it turns off Boot's defaults), `JobBuilder`/`StepBuilder` with explicit `JobRepository` and `PlatformTransactionManager`, and long ids.

Measure: job duration, items per second, failure and skip counts, restart count.
**Follow-ups:**
- What makes two launches the same JobInstance?
- Where is the reader's position stored?
- How do you prevent running the same job concurrently?
**Pitfalls:**
- Using a random parameter and expecting restart to resume.
- Storing the repository in a different database than the data.
- Not understanding that a COMPLETED instance cannot rerun.

### Q72. How does chunk-oriented processing work, and where are the transaction boundaries?
**Tags:** Spring Batch, Intermediate, Concept
**Analogy:** Packing 500 items per box: you only seal and ship a box when it is full and all items passed inspection; if one item breaks, you redo the box, not the whole warehouse.
**Model answer:**
A chunk step reads items one at a time, optionally processes each, collects `chunkSize` outputs and passes the whole list to the writer, all within one transaction. On success the transaction commits and the step's `ExecutionContext` and counters are saved to the `JobRepository`; then the next chunk starts.

Bulk job configuration (CONTRACT section 7): `readValidateStep` with chunk 500, a streaming `FlatFileItemReader` (never loading the whole file), `RecipientRowProcessor` (validation, recipient resolution, dedupe), `BulkMessageWriter` (creates `VALIDATED` `message_request` rows via `NotificationService.submitInternal`).

Behavior on errors:
- A writer exception rolls back the chunk. With fault tolerance, the framework retries (for configured transient exceptions) and then, for skippable exceptions, splits the chunk and reprocesses items one by one to identify the failing item (scan mode), then skips it. The processor is therefore re-invoked for items: processors must be idempotent and side-effect free, or `processorNonTransactional()` used with care.
- Reader exceptions are skipped without chunk rollback if allowed.
- A non-skippable exception fails the step, leaving the `JobRepository` positioned at the last committed chunk for restart.

Tuning: chunk size trades commit overhead against rollback cost and memory (and lock hold time). Typical 100-1000; choose by measurement. Keep reads streaming, set `fetchSize` on JDBC readers, and use `saveState` appropriately (not for multi-threaded readers).

Idempotent writes (key `bulk-<jobId>-<externalRef>`) make reprocessing after rollback or restart safe.
**Follow-ups:**
- Why must processors be idempotent?
- What is scan mode?
- What does `saveState=false` do?
**Pitfalls:**
- Side effects (sending messages) inside the processor.
- Huge chunks that hold locks.
- Reading the whole file into memory.

### Q73. How would you recover a bulk Spring Batch job after failure?
**Tags:** Spring Batch, Senior, Scenario
**Analogy:** A bookmark in a long book: after you drop the book you resume from the bookmark rather than from page one, and you check you do not reread and re-act on pages already done.
**Model answer:**
Clarify: how did it fail (application exception, pod kill, database outage, bad data), how far did it get, are partial results visible to customers (messages already queued), and what is the business deadline?

Triage with the job repository and bulk-job views: `BulkJobStatus` and counts (`total`, `processed`, `succeeded`, `failed`, `duplicate`, CONTRACT section 4), the `BatchStatus`/`ExitStatus` of the last `StepExecution`, and `bulk_job_failure` rows.

Recovery path (CONTRACT section 7):
1. Fix the root cause: data errors (bad rows beyond `skipLimit`), infrastructure (DB connectivity), configuration.
2. If the execution status is `FAILED` or `STOPPED`: call `POST /bulk-jobs/{id}/restart`, which relaunches with the same `JobParameters`, so Spring Batch creates a new `JobExecution` on the same `JobInstance` and resumes from the last committed chunk using the saved `ExecutionContext` (reader line number).
3. If the process died hard, the execution may be stuck in `STARTED` (the repository thinks it is running); mark it `FAILED`/abandoned through the `JobOperator` or an operator procedure after confirming no live process runs it, then restart.
4. Safety on re-execution: the writer is idempotent using key `bulk-<jobId>-<externalRef>` plus `UNIQUE(job_id, externalRef)`, so rows from a chunk that committed but whose metadata did not are skipped as duplicates, not duplicated (counted in `duplicate`).
5. Throttle on restart so the backlog does not hit providers at once (`rate_limit_per_sec`).

Failure modes: restart with changed identifying parameters creates a new instance and reprocesses everything (duplicates blocked only by the idempotency keys); input file changed or deleted between runs; multi-threaded step restart semantics.

Measure: rows per second after restart, duplicate count (should equal the rows in the final partial chunk), time to recover.
**Follow-ups:**
- What does ABANDONED mean?
- What if the CSV file was modified?
- How do you test restartability?
**Pitfalls:**
- Restarting with new identifying parameters.
- Non-idempotent writer.
- Restarting while another execution is truly still running.

### Q74. Explain Spring Batch skip and retry policies and how you would configure them for a bulk send.
**Tags:** Spring Batch, Intermediate, Concept
**Analogy:** Retry is "try the stuck door again in a moment"; skip is "this door is painted shut, note it down and move to the next one".
**Model answer:**
Fault tolerance is configured on a step with `faultTolerant()`:
- Retry: for transient failures in processing or writing (deadlock, temporary DB connectivity, optimistic lock failure). `retry(TransientDataAccessException.class).retryLimit(3)` with a backoff policy. Retrying re-executes the item or chunk, so operations must be idempotent. Combine with exponential backoff to avoid hammering the database.
- Skip: for item-level bad data (validation error, malformed row). `skip(ValidationException.class).skipLimit(100)`; or a custom `SkipPolicy`. Each skipped item is reported to a `SkipListener` and the platform records it in `bulk_job_failure` (job id, line number, reason, `raw_line_hash`, CONTRACT section 4), storing a hash rather than the raw line to minimize personal data.
- Order of operations: retry first for retryable exceptions; when retries are exhausted, skip if the exception is skippable; otherwise fail the step.
- `skipLimit`: protects against a systemically bad file (for example wrong delimiter) from "succeeding" with 99 percent skipped. A job that exceeds the limit fails and finishes `FAILED`; a job with skips but under the limit ends `COMPLETED_WITH_ERRORS` (CONTRACT section 3.2).
- Never skip infrastructure exceptions; never retry validation errors.

Bulk send settings (CONTRACT section 7): skip up to `skipLimit` bad rows; retry transient DB errors 3 times.

Rate and capacity: retry delays must not exceed transaction timeouts.

Measure: skip count by reason, retry count, percentage of skipped rows, job final status.

<!-- in-project:b44 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/batch/BulkJobConfig.java`
>
> From the class Javadoc. Counting rule (DEVIATIONS): `processed = succeeded + failed + duplicate`; duplicates are filtered, not skipped, so they do not count against the skip limit; status is `COMPLETED_WITH_ERRORS` iff `failed > 0`.
>
> ```java
> /*
>  *  - chunk size messaging.bulk.chunk-size; one transaction per chunk (messages, outbox, audit, counters)
>  *  - fault tolerant: up to messaging.bulk.skip-limit bad rows are skipped and recorded; one more fails the
>  *    job; transient database errors are retried retry-limit times with exponential back-off
>  *  - JDBC job repository (V100__spring_batch.sql); spring.batch.job.enabled=false: jobs are only
>  *    launched through BulkJobService
>  */
> ```

**Follow-ups:**
- What happens to the chunk when an item is skipped in the writer?
- How do you expose the failures to users?
- How do you handle a file where every row fails?
**Pitfalls:**
- Unlimited skip.
- Retrying non-idempotent writes.
- Skipping exceptions that indicate infrastructure problems.

### Q75. Design a bulk send of 2 million recipients from a CSV with throttling, deduplication and progress reporting.
**Tags:** Spring Batch, Staff, Scenario
**Analogy:** A mail room that processes a huge mailing list in trays of 500, ignoring repeated names, checking each tray and telling the manager how far it has got, without overwhelming the courier.
**Model answer:**
Clarify: deadline (for example 4 hours), provider rate limits, consent rules, file format and size, who can run it, and the reporting needed.

Approach (CONTRACT sections 5 and 7):
- Intake: `POST /api/v1/bulk-jobs` multipart upload requires role `SENDER` plus scope `messages:bulk`; stream to disk (never memory), enforce `messaging.bulk.max-rows`, validate headers, return 202 with job id.
- Job: `bulkSendJob` chunk 500, streaming reader, `RecipientRowProcessor` (column validation, resolve recipient, in-chunk set plus `(job_id, externalRef)` unique constraint for dedupe), `BulkMessageWriter` creates `VALIDATED` messages with key `bulk-<jobId>-<externalRef>` and policy evaluation at dispatch time.
- Throughput math: 2,000,000 rows at 500 rows per chunk is 4,000 chunks; at 100 ms per chunk transaction that is about 400 seconds to create rows; the real limit is the provider rate (for example 500 msg/s means 4,000 seconds, about 67 minutes). The writer-side `RateLimiter` paces enqueueing; the normal provider limiter protects the downstream.
- Concurrency: default 4 (`bulk.concurrency`), only with a thread-safe reader (`SynchronizedItemStreamReader`) or partitioning by file range; measure whether the DB or provider is the bottleneck.
- Quiet hours and priority: campaign category `MARKETING`, priority `LOW`; policy defers messages inside quiet hours; security alerts overtake campaigns in priority queues.
- Progress: `bulk_job` counters, `messaging.bulk.progress{jobId}` gauge, polled by the UI. Use a bounded set of metric tags (do not leave per-job gauges forever).
- Control: stop and restart endpoints; failures listing with pagination.

Failure modes: file with all rows invalid (skip limit), restarts (idempotency), provider outage (retry/circuit breaker, job continues enqueuing but dispatch holds), duplicate recipients across files (documented behavior).

Measure: rows/s, enqueue lag, dispatch delay, skip ratio, bounce/complaint rates during the campaign with an automatic stop threshold.

<!-- in-project:b14 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/batch/TokenBucketRateLimiter.java`
>
> The bulk writer throttles with this limiter (`permitsPerSecond <= 0` means unlimited). The clock and sleeper are injected, so the unit test needs no real sleeping.
>
> ```java
> public void acquire(int permits) {
>     if (intervalNanos == 0 || permits <= 0) {
>         return;
>     }
>     long waitNanos;
>     synchronized (this) {
>         long now = nanoTime.getAsLong();
>         if (!started) {
>             started = true;
>             nextFree = now - burstNanos;
>         }
>         long earliest = Math.max(nextFree, now - burstNanos);
>         nextFree = earliest + intervalNanos * permits;
>         waitNanos = Math.max(0, earliest - now);
>     }
>     if (waitNanos > 0) {
>         sleeper.accept(waitNanos);
>     }
> }
> ```

**Follow-ups:**
- How would you split the job across several pods?
- How do you preview the audience before sending?
- How do you honor opt-outs that arrive mid-campaign?
**Pitfalls:**
- Loading the file into memory.
- Sending from inside the batch process directly.
- No stop button or auto-stop on bounce rate.

### Q76. Compare multi-threaded steps, partitioning, and parallel steps in Spring Batch. When is each safe?
**Tags:** Spring Batch, Senior, Concept
**Analogy:** Multi-threading is several workers sharing one conveyor belt; partitioning is giving each worker their own separate pile of work.
**Model answer:**
- Multi-threaded step: one step, a `TaskExecutor` runs chunks in parallel threads. The reader is shared and must be thread-safe: wrap non-thread-safe readers in `SynchronizedItemStreamReader`. Restart state (`saveState`) is unreliable because the reader position becomes ambiguous with parallel chunks (set `saveState=false` or accept re-processing with idempotent writes). Simplest to enable; the platform allows it only with a synchronized reader (CONTRACT section 7).
- Partitioning: a manager step splits input into partitions (by id range, file split, or hash) and each partition is a separate worker `StepExecution` with its own `ExecutionContext`, reader and restart state. It can run locally with threads or remotely across nodes (remote partitioning with messaging, or Kubernetes jobs). Restartable per partition; best for large scale and exact restart semantics. Requires deterministic, non-overlapping partitions.
- Parallel steps (split flow): independent steps run concurrently, such as two unrelated exports.
- AsyncItemProcessor/AsyncItemWriter: parallelize slow processing (external lookups) while the writer receives futures.

Choosing:
- CPU or I/O-bound processing with a single file: multi-threaded step with a synchronized reader, or async processor.
- Large database-sourced datasets with a range key: partitioning (and per-partition restart).
- Remember the true bottleneck: the database connection pool and downstream provider limits. More threads than pool connections only queue. Rate limits apply across all threads (shared limiter).

Risks: ordering lost, deadlocks on shared rows, duplicates of dedupe sets across threads (dedupe must rely on the database unique constraint, not an in-memory set).

Measure: speedup versus thread count, DB pool saturation, restart correctness test.

<!-- in-project:b13 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/batch/BulkJobConfig.java`
>
> The project uses a multi-threaded step (`concurrency` workers behind a `SynchronizedItemStreamReader`), not partitioning. With more than one worker the reader cannot checkpoint an exact position, so `saveState` is off and a restart re-reads the file; the ledger and the key `bulk-<jobId>-<externalRef>` make processed rows no-ops (DEVIATIONS, batch module).
>
> ```java
> @Bean(name = "bulkStepExecutor")
> public ThreadPoolTaskExecutor bulkStepExecutor(MessagingProperties props) {
>     int n = Math.max(1, props.bulk().concurrency());
>     ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
>     ex.setThreadNamePrefix("bulk-chunk-");
>     ex.setCorePoolSize(n);
>     ex.setMaxPoolSize(n);
>     ex.setQueueCapacity(1000);
>     ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
>     // ...
>     return ex;
> }
> ```

**Follow-ups:**
- Why does `saveState` become unreliable?
- How do you partition a CSV file?
- How do you size the grid for remote partitioning?
**Pitfalls:**
- Sharing a non-thread-safe reader.
- Assuming linear speedup.
- In-memory dedupe across threads.

### Q77. After a pod was killed mid-run, the bulk job shows STARTED and refuses to restart ("job instance already running"). What do you do?
**Tags:** Spring Batch, Senior, Scenario
**Analogy:** A sign on the workshop door says "occupied" because the worker left in a hurry and never flipped it back.
**Model answer:**
Clarify: is any process still running the job (check pods and the job's `last_updated`/heartbeat), and how long since the last chunk commit?

Cause: the `JobRepository` records the execution with status `STARTED` and no end time. A hard kill skips the listener that would mark it `FAILED`. Spring Batch refuses a restart while it believes an execution is still running (`JobExecutionAlreadyRunningException`).

Verification steps:
- Query `BATCH_JOB_EXECUTION` for status `STARTED` and `END_TIME` null, check `LAST_UPDATED`; compare with `BATCH_STEP_EXECUTION` commit counts and the `bulk_job` progress counters (are they still moving?).
- Confirm no live pod owns it (distributed deployments might run the same job elsewhere).

Fix:
- Use `JobOperator.abandon`/`stop` or an operator procedure that sets the stuck execution to `FAILED` with an end time (and the step execution too), then call restart (`POST /bulk-jobs/{id}/restart`, CONTRACT section 7).
- Do it through application code or a documented SQL runbook with audit, not ad hoc.

Prevention:
- A startup recovery component that marks executions orphaned by a previous instance of the same pod as `FAILED` (use heartbeat timestamps or instance id).
- Graceful shutdown (`SmartLifecycle` with a 30 s timeout, CONTRACT section 6) so a job gets `STOPPED` and state saved.
- Heartbeat and alert on executions with no progress for N minutes.
- Run bulk jobs in dedicated worker pods whose terminations are controlled (PodDisruptionBudget, `terminationGracePeriodSeconds`).

Measure: count of orphaned executions, recovery time.
**Follow-ups:**
- How do you tell a running job from an orphaned one?
- Is `STOPPED` restartable and `ABANDONED` not?
- What about a database restore?
**Pitfalls:**
- Deleting rows from batch tables manually.
- Restarting while the original is alive.
- Launching with a new run id to bypass the check.

### Q78. After a restart, some recipients received the message twice. Where is the gap?
**Tags:** Spring Batch, Senior, Scenario
**Analogy:** The post worker re-delivered a tray whose delivery was already stamped, because the stamp sheet was lost in the crash.
**Model answer:**
Clarify: duplicates created at row creation (two `message_request` rows) or at dispatch (one row, two sends)? Query by `bulk_job_id` and `externalRef` to see which.

Common causes:
- Row creation duplicates: the writer was not idempotent, or the restart used new identifying `JobParameters` (a new job id), so the idempotency key `bulk-<jobId>-<externalRef>` differs. Also duplicate rows in the input file (documented behavior). The platform defends with key `bulk-<jobId>-<externalRef>`, unique `(job_id, externalRef)` and `UNIQUE(tenant_id, idempotency_key)` (CONTRACT sections 4 and 7).
- Side effect inside the batch process: if the batch step called the provider directly, a chunk rollback after the call duplicates sends. The platform design keeps the batch step to creating `VALIDATED` rows only; sending is a separate consumer pipeline.
- Chunk replays after skip scan mode re-running processors with external effects.
- At dispatch: ambiguous provider outcomes retried blindly (see the timeout questions): different cause, different fix.

Fixes:
- Make the writer idempotent via the database unique key and treat duplicate-key as success (count as `duplicate`).
- Keep processors pure.
- Ensure restart reuses identical identifying parameters; use `POST /bulk-jobs/{id}/restart`.
- Add a reconciliation query: group by `(bulk_job_id, recipient_id)` having count greater than 1, alert if it is nonzero.

Test: kill the job between chunk commit and metadata update (simulate), restart, assert row counts equal input unique rows.

Measure: duplicate counter per job, post-run uniqueness check.
**Follow-ups:**
- Why not put the send in the writer?
- How is the duplicate count different from skip count?
- What if two files contain the same recipient?
**Pitfalls:**
- Side effects in the batch step.
- A random run id in parameters.
- Dedupe only in memory.

### Q79. You must tune a batch job from 1,000 to 10,000 rows per second. How do you approach it?
**Tags:** Spring Batch, Senior, Scenario
**Analogy:** Finding the narrowest part of a pipe before buying a bigger pump.
**Model answer:**
Clarify the target and the measured baseline; never tune before profiling. Define where time goes per chunk: read, process, write, commit.

Method:
1. Instrument: step execution metrics (`spring.batch.*` timers in Micrometer), time per phase, DB wait, GC. Use a representative dataset (a synthetic generator, `SyntheticDatasetGenerator`, CONTRACT section 7), and record numbers only when measured.
2. Find the bottleneck:
 - Reader: use streaming, correct `fetchSize` for JDBC, avoid per-row lookups (cache or batch lookups such as resolving recipients through an `IN` query per chunk).
 - Processor: eliminate N+1 queries; prefetch recipients for the whole chunk.
 - Writer: JDBC batch inserts (`batchUpdate`), Hibernate `batch_size` and ordered inserts, UUID or sequence allocation, drop unnecessary indexes during load only if safe, avoid per-row audit writes (batch them).
 - Commit: chunk size 500 to 1000; larger chunks lengthen locks and rollback cost.
3. Concurrency: multi-threaded step or partitions, bounded by the Hikari pool and DB CPU; check lock contention on unique indexes and sequences.
4. Downstream limit: if the throttle (`rate_limit_per_sec`) or provider rate caps throughput, tuning the job further is pointless; separate "row creation rate" from "send rate".

Typical wins: batch lookups (10x), JDBC batching (5 to 10x), indexes trimmed, connection pool aligned. Typical losses: bigger pools causing lock contention, huge chunks, synchronous remote calls per item.

Validate with repeat runs, compare p95 chunk time, and ensure correctness (counts and uniqueness) after every optimization. Measure: rows/s, p95 chunk duration, DB CPU, wait events.
**Follow-ups:**
- What would you do if the database CPU is saturated?
- How does chunk size affect restart cost?
- When is partitioning worth the complexity?
**Pitfalls:**
- Guessing and changing five things at once.
- Ignoring the provider throttle.
- Optimizing without a correctness check.

## SQL, Isolation and Oracle

### Q80. How do you design a composite index, and what does column order mean for the message list queries?
**Tags:** SQL, Intermediate, Concept
**Analogy:** A phone book sorted by last name then first name: you can look up "Smith" or "Smith, John" quickly but not all "Johns" without scanning.
**Model answer:**
A B-tree composite index sorts rows by the first column, then the second within equal first values, and so on. The leftmost-prefix rule says an index on `(a, b, c)` supports predicates on `a`, `a,b`, and `a,b,c`, but not efficiently on `b` alone (some databases use skip scan, with caveats).

Design guidance:
- Equality columns first, then the range or sort column last. The platform's index `(tenant_id, status, created_at DESC)` (CONTRACT section 4) serves `WHERE tenant_id=? AND status=? ORDER BY created_at DESC LIMIT 50` with no sort step and early termination.
- Selectivity matters, but the pattern of usage matters more: tenant isolation is in every query, so `tenant_id` leads.
- Covering indexes (including the selected columns, `INCLUDE` in PostgreSQL 11+ and SQL Server) allow index-only scans; they cost write speed and space.
- Every index slows inserts and updates and consumes buffer cache; on a hot table (`message_request`, status changes frequently) keep only indexes backed by real queries. Updating an indexed column (like `status`) rewrites index entries.
- Index the foreign keys you join or delete by (`recipient_id, created_at`; `bulk_job_id`).
- Unique indexes are constraints: `UNIQUE(tenant_id, idempotency_key)`, `UNIQUE(provider_id, provider_event_id)` provide correctness, not just speed.
- Functions on columns defeat indexes (`WHERE LOWER(email)=?`), unless a function-based index is defined; implicit type conversion also does.
- Partial/filtered indexes are not used here to stay portable between H2 and Oracle (CONTRACT section 1).

Check with `EXPLAIN (ANALYZE, BUFFERS)` (PostgreSQL) or `EXPLAIN PLAN`/`DBMS_XPLAN` (Oracle).
**Follow-ups:**
- Why does `status` precede `created_at`?
- When is a low-selectivity column still worth indexing?
- What is an index-only scan?
**Pitfalls:**
- One index per column.
- Wrong column order.
- Wrapping indexed columns in functions.

### Q81. A support query on the message table was fast last month and now takes 20 seconds. How do you read the plan and fix it?
**Tags:** SQL, Senior, Scenario
**Analogy:** The GPS suddenly picks a back road through town instead of the highway; you need to see the route it chose and why.
**Model answer:**
Clarify the exact SQL and parameters, the data growth, recent deploys or schema changes, and whether it is slow every time or only for some tenants (skew).

Diagnosis:
1. Get the actual plan with runtime numbers: `EXPLAIN (ANALYZE, BUFFERS)` in PostgreSQL; in Oracle use `DBMS_XPLAN.DISPLAY_CURSOR` with `GATHER_PLAN_STATISTICS`. Compare estimated and actual rows: large mismatches indicate bad statistics.
2. Read the shape: sequential/full table scan on a big table, nested loop with a huge outer set, sort spilled to disk, missing index, or the planner not using an index because of a function or type cast or a low-selectivity predicate.
3. Check statistics freshness: `ANALYZE`/autovacuum (PostgreSQL), `DBMS_STATS` (Oracle). Data skew (one tenant has 80 percent of rows) can make a single plan bad for others (bind peeking issues in Oracle, generic plans in PostgreSQL prepared statements).
4. Check bloat and vacuum, locks (`pg_stat_activity` waiting), and I/O.

Fixes in order: update statistics; add or reshape the index to match filter and sort (`(tenant_id, status, created_at DESC)` style); rewrite the query (avoid `OR` across columns, avoid leading wildcards in `LIKE`, replace offset with keyset), add a date range to bound the scan, partition by time for retention, and only as a last resort use hints or plan baselines/profiles.

Prevent regressions: keep a slow-query log (`log_min_duration_statement`, AWR in Oracle), a performance test with realistic data volume, and alerts on p95 query time.

Measure: execution time, buffers read, rows scanned versus returned.

<!-- in-project:b24 -->
> [!NOTE] In this project
> **File:** `backend/src/main/resources/db/migration/V50__dispatch_indexes.sql`
>
> Each index was added for a named query: equality column first (`status`), range column second. `V3__messages.sql` also has `idx_msg_tenant_status_created (tenant_id, status, created_at DESC)` for the list API. No execution plans from a real PostgreSQL were captured in this repository.
>
> ```sql
> -- StuckMessageSweeper: PROCESSING with an expired lock
> CREATE INDEX idx_msg_status_locked ON message_request (status, locked_until);
>
> -- sweeper (SENT_TO_PROVIDER past the receipt timeout), reconciliation (DELIVERY_UNKNOWN older than reconcile-after),
> -- poller (orphaned VALIDATED messages)
> CREATE INDEX idx_msg_status_changed ON message_request (status, status_changed_at);
>
> -- reconciliation bookkeeping: count and latest RECONCILIATION_CHECK per message
> CREATE INDEX idx_audit_entity_action ON audit_event (entity_type, entity_id, action, occurred_at);
> ```

**Follow-ups:**
- What does a big estimated versus actual mismatch tell you?
- What is a covering index scan?
- When do you use hints?
**Pitfalls:**
- Adding indexes blindly.
- Looking at the estimated plan only.
- Ignoring statistics.

### Q82. Explain transaction isolation levels and the anomalies each prevents. Which would you use for status updates?
**Tags:** SQL, Senior, Concept
**Analogy:** Different levels of "do not disturb" signs on a shared whiteboard: the stricter the sign, the fewer surprises you see but the more people wait.
**Model answer:**
Anomalies:
- Dirty read: reading uncommitted data of another transaction.
- Non-repeatable read: re-reading a row returns a different value because another transaction committed a change.
- Phantom read: re-running a range query returns new or missing rows.
- Lost update: two read-modify-write cycles overwrite each other.
- Write skew: two transactions read overlapping data and update disjoint rows, violating an invariant.

SQL levels: READ UNCOMMITTED (allows dirty reads; PostgreSQL treats it as READ COMMITTED), READ COMMITTED (default in PostgreSQL and Oracle; each statement sees committed data as of its start), REPEATABLE READ (snapshot per transaction; PostgreSQL's implementation prevents phantoms too but raises serialization failures; MySQL InnoDB default with gap locks), SERIALIZABLE (PostgreSQL SSI detects dangerous patterns and aborts; Oracle's serializable is snapshot isolation and can still show write skew).

Implementation: MVCC databases (PostgreSQL, Oracle) let readers not block writers. Under READ COMMITTED, `UPDATE ... WHERE id=? AND status='QUEUED'` is safe as a compare-and-set because the update re-checks the predicate after waiting for a conflicting row lock.

Recommendation: keep READ COMMITTED, and add explicit protection where needed: atomic conditional updates (CAS) for status transitions (CONTRACT section 6), `@Version` optimistic locking for human edits, unique constraints for dedupe, `SELECT ... FOR UPDATE [SKIP LOCKED]` for queue claims. Use SERIALIZABLE only for narrow invariants and be ready to retry on serialization failure (SQLSTATE 40001).

Measure: serialization failure rate, deadlocks, lock wait time.
**Follow-ups:**
- Why is read-then-write at READ COMMITTED a lost-update risk?
- How does Oracle's isolation differ from PostgreSQL's?
- What is write skew with an example?
**Pitfalls:**
- Using SERIALIZABLE everywhere.
- Thinking `@Transactional` alone prevents lost updates.
- Confusing isolation with locking.

### Q83. Under load the database reports deadlocks on message_request status updates. How do you analyze and eliminate them?
**Tags:** SQL, Senior, Scenario
**Analogy:** Two cars on a narrow bridge facing each other, each waiting for the other to back up.
**Model answer:**
Clarify: which statements deadlock (PostgreSQL logs both queries with SQLSTATE 40P01; Oracle writes a trace file with ORA-00060), how frequently, and which code paths touch the rows.

Typical causes in this domain:
- Two transactions update the same set of rows in different orders (a batch update by status versus single-row updates by id).
- Foreign key checks locking parent rows (`recipient`, `bulk_job` counter updates: many consumers incrementing a single `bulk_job` row create a hot row).
- Long transactions that hold locks while calling external services.
- Missing indexes on foreign keys (Oracle takes table-level locks in some cases for unindexed foreign keys when parent rows are updated or deleted).
- Unique index insert races.

Fixes:
- Consistent lock ordering: always process ids in sorted order in multi-row updates.
- Keep transactions short, no remote calls inside.
- Use atomic single-statement updates (CAS) instead of read, then write.
- Hot counters: avoid per-message updates to one row; accumulate in memory or in batch chunks, or use sharded counters, or compute from the table when read.
- Use `SKIP LOCKED` for queue claims so workers do not wait on each other.
- Retry on deadlock (`CannotAcquireLockException`) with a small randomized backoff; deadlock victims are safe to retry only if the transaction is idempotent.
- Add the missing foreign key indexes.

Verification: reproduce with a concurrent test; examine lock graph (`pg_locks`, `v$lock`); log deadlock details.

Measure: deadlocks per hour, lock wait time, retry counts, transaction duration p95.
**Follow-ups:**
- Why is retry a valid cure but not a root-cause fix?
- How do hot-row counters scale?
- How does `SKIP LOCKED` change semantics?
**Pitfalls:**
- Increasing the lock timeout.
- Ignoring unindexed foreign keys.
- Retrying non-idempotent transactions.

### Q84. Write SQL for two common support questions: the latest delivery event per message, and recipients with more than one message for the same client reference in a job. Explain the approach.
**Tags:** SQL, Intermediate, Concept
**Analogy:** For each parcel, find the newest tracking stamp; and spot any person who got two copies of the same letter.
**Model answer:**
Latest event per message using a window function (portable to PostgreSQL, Oracle and recent H2):

```sql
SELECT message_id, type, occurred_at
FROM (
  SELECT e.message_id, e.type, e.occurred_at,
         ROW_NUMBER() OVER (PARTITION BY e.message_id
                            ORDER BY e.occurred_at DESC, e.id DESC) AS rn
  FROM delivery_event e
  WHERE e.applied = TRUE
    AND e.occurred_at >= :from AND e.occurred_at < :to
) t
WHERE t.rn = 1;
```

Notes: bound the time range so the plan can use an index; add a deterministic tie-breaker (`id`); `applied = TRUE` limits to events that influenced state. In PostgreSQL `DISTINCT ON (message_id)` is a shorter, nonportable alternative. A correlated subquery with `MAX(occurred_at)` works but may scan more.

Duplicates within a bulk job:

```sql
SELECT recipient_id, COUNT(*) AS copies
FROM message_request
WHERE bulk_job_id = :jobId
GROUP BY recipient_id
HAVING COUNT(*) > 1;
```

Uses the `(bulk_job_id)` index (CONTRACT section 4); on huge jobs, check the plan and consider a limit. This query doubles as a reconciliation check (expected zero rows because of `UNIQUE(job_id, externalRef)` and idempotency keys).

Other useful patterns: `NOT EXISTS` for anti-joins (for example recipients without preferences), `LEFT JOIN ... WHERE x IS NULL`, `COUNT(*) FILTER (WHERE ...)` (PostgreSQL) or `SUM(CASE WHEN ...)` (portable) for status counts by channel.

Pitfalls to avoid: `NOT IN` with nullable subqueries (returns no rows when any NULL is present), `SELECT *`, functions on indexed columns, and unbounded scans on append-only tables.

Measure: plan shape, rows scanned, runtime on realistic volume.
**Follow-ups:**
- Why is `NOT IN` dangerous with NULLs?
- How would you write the first query without window functions?
- How does a covering index help?
**Pitfalls:**
- No time bound on event queries.
- Non-deterministic ties.
- Using `NOT IN` on nullable columns.

### Q85. The message and delivery_event tables grow by 50 million rows per month. How do you handle retention, partitioning and query performance?
**Tags:** SQL, Staff, Scenario
**Analogy:** A filing cabinet with one drawer per month: when the retention period passes, you remove the whole drawer instead of pulling out pages one by one.
**Model answer:**
Clarify: legal retention (for example messages 90 days, audit 7 years; the platform exposes `messaging.retention.{message-days,audit-days,event-days}`, CONTRACT section 9), query patterns (mostly recent data, support lookups by id or recipient), and archive requirements.

Capacity: 50 million rows per month at roughly 1 KB is about 50 GB per month plus indexes (often 1 to 2 times the table size); one year is 600 million rows. Row-by-row deletes at that scale generate heavy WAL/redo, bloat and long locks.

Approach:
- Range partition by `created_at` (monthly or weekly). PostgreSQL declarative partitioning or Oracle interval partitioning creates partitions automatically. Retention becomes `DROP PARTITION` or detach (near-instant, no bloat). Constraint: the partition key must be part of unique constraints in PostgreSQL (so the `UNIQUE(tenant_id, idempotency_key)` design needs care; global uniqueness may need a separate small lookup table such as `idempotency_record` with its own TTL).
- Queries include the time range to enable partition pruning; local indexes per partition keep index size manageable.
- Retention job (`RetentionJob`, CONTRACT section 2) drops or archives expired partitions: export to object storage (compressed Parquet/CSV) if required, then drop.
- Hot/cold: recent partitions on fast storage; older read-only.
- Audit events stay append-only, with longer retention and possibly separate storage.
- Keep PII minimal; payloads hold variables not rendered bodies (CONTRACT section 4).
- Monitoring: partition creation failures (missing future partitions cause insert errors; pre-create), table and index sizes, vacuum/bloat.

The contract keeps DDL portable for H2 and defers partitioning to production DDL variants (document in database docs); I would state it as a production hardening step rather than claim it is implemented.

Measure: insert latency, query p95, storage growth, drop time, archive success.

<!-- in-project:b22 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/scheduler/RetentionJob.java`
>
> Retention is batched deletes (`batch-size`, `pause-ms`, `max-batches-per-table`) with a dry-run switch, not partition drops. Partitioning by time is the design answer for 50 million rows per month; the reference implementation documents it in `docs/database/retention-and-archival.md`.
>
> ```java
> @Scheduled(cron = "${messaging.retention.cron:0 30 2 * * *}")
> public void scheduled() {
>     if (!retention.enabled()) {
>         return;
>     }
>     try {
>         run();
>     } catch (RuntimeException e) {
>         log.error("retention run failed: {}", e.getClass().getSimpleName());
>     }
> }
> // run(): purges message_request, delivery_event, audit_event, idempotency_record,
> // processed_message, outbox_event in batches; a dry-run mode only counts.
> ```

**Follow-ups:**
- Why can't a unique index span partitions in PostgreSQL?
- How do you migrate an existing large table to partitions online?
- What do you do about foreign keys to a partitioned table?
**Pitfalls:**
- Using `DELETE` for retention on huge tables.
- Forgetting future partitions.
- Queries without the partition key.

### Q86. Oracle sequences versus UUID primary keys: how do you choose, and what are the implications for Hibernate?
**Tags:** Oracle, Intermediate, Concept
**Analogy:** A numbered ticket dispenser (sequence) versus everyone drawing a unique random sticker (UUID).
**Model answer:**
Sequences (`CREATE SEQUENCE ... CACHE 100`, Oracle 12c also offers identity columns backed by sequences):
- Compact (8 bytes), ordered inserts keep the B-tree right-growing and cache-friendly, easy to read.
- Single point of generation: a sequence with a small cache becomes a contention point; use `CACHE` and, in RAC, `ORDER` only if needed (it hurts performance). Gaps are normal (rollback, cache loss); do not use them as gapless business numbers.
- Hibernate: `@SequenceGenerator(allocationSize=50)` matched to the sequence `INCREMENT BY 50` lets Hibernate pre-allocate ids (pooled optimizer) and still use JDBC batching.
- Predictable ids can leak volume and enable enumeration attacks (IDOR); expose opaque public ids.

UUIDs (the platform uses `VARCHAR(36)` UUID ids, CONTRACT section 4):
- Generated in the application without a DB round trip, safe across systems and regions, good for merging data and distributed generation, no enumeration.
- Cons: 36 character strings are large (use `RAW(16)`/native `uuid` type to reduce to 16 bytes), random ids fragment B-tree indexes and reduce cache locality and insert speed; mitigate with time-ordered UUIDs (UUIDv7, ULID) which keep inserts mostly append-like.
- Portability: the contract uses `VARCHAR(36)` to run on H2, PostgreSQL and Oracle with identical DDL, trading space for portability.

Choice: sequences for high-volume internal tables where insert performance and size dominate; UUIDs when generation is distributed, ids are exposed externally, or the DB product may change. For the largest tables, consider time-ordered UUIDs stored in compact binary form.

Measure: insert rate, index size, buffer cache hit ratio, page splits.
**Follow-ups:**
- Why must allocationSize match the sequence increment?
- What are gaps and are they a problem?
- How does UUIDv7 help?
**Pitfalls:**
- Mismatched allocation size and increment.
- Expecting gapless sequences.
- Exposing sequential ids publicly.

### Q87. Compare Oracle MERGE with upsert approaches in PostgreSQL and H2, and explain how you would implement an idempotent "insert if absent" safely.
**Tags:** Oracle, Senior, Concept
**Analogy:** "Update the contact if it exists, otherwise add a new one", done as one indivisible step so two clerks do not both add the same person.
**Model answer:**
Oracle `MERGE INTO target USING source ON (cond) WHEN MATCHED THEN UPDATE ... WHEN NOT MATCHED THEN INSERT ...` is an upsert in one statement. Notes: it is not fully atomic against concurrent inserts of the same key: two sessions may both find no match and one fails with a unique violation (ORA-00001); rely on a unique constraint and handle the exception or retry. Updating the join key in the `ON` clause is not allowed.

PostgreSQL: `INSERT ... ON CONFLICT (key) DO UPDATE | DO NOTHING` (PostgreSQL 15 also supports standard `MERGE`, with similar concurrency caveats). H2 supports `MERGE INTO ... KEY(...)` and standard `MERGE`. The platform contract bans PG-only syntax such as `ON CONFLICT` so migrations and queries run on H2 and Oracle (CONTRACT section 1).

Portable pattern for idempotent insert (for dedupe tables like `processed_message`, `replay_guard`, `idempotency_record`):
1. Try `INSERT` relying on a `UNIQUE` constraint.
2. Catch `DuplicateKeyException`/`DataIntegrityViolationException` and treat as "already exists".
3. In a transaction on PostgreSQL, a failed statement aborts the transaction (unless a savepoint is used), so perform the insert in its own small transaction (`REQUIRES_NEW`) or use a savepoint; this is a well-known gotcha.
Alternative: `UPDATE ... WHERE` CAS first, then insert on zero rows with retry.

State transitions use CAS updates, not MERGE. For counters and aggregates, an atomic `UPDATE x SET n = n + 1` is better than read, modify, write.

Measure: duplicate-key rate (it is a signal of retries and duplicate deliveries, not an error), unique violation latency.
**Follow-ups:**
- Why does a PostgreSQL transaction abort after a unique violation?
- How would you write the portable version in JPA?
- What are MERGE's concurrency limits?
**Pitfalls:**
- Check-then-insert races.
- Catching the exception inside the same PG transaction and continuing.
- Using vendor syntax in shared migrations.

### Q88. Name differences between Oracle and PostgreSQL that bite during a port or when keeping reference DDL for both.
**Tags:** Oracle, Senior, Concept
**Analogy:** Two dialects of one language: the grammar looks similar but a few words mean different things.
**Model answer:**
Notable differences (the platform keeps Oracle reference DDL and mapping notes alongside the PostgreSQL/H2 migrations, CONTRACT section 1):
- Empty string: Oracle treats `''` as NULL for `VARCHAR2`; PostgreSQL treats it as a distinct empty string. This affects `NOT NULL` columns, unique indexes, and comparisons.
- Types: `VARCHAR2(n CHAR)`, `NUMBER`, `CLOB` versus `VARCHAR`, `NUMERIC`, `TEXT`; Oracle has `DATE` with a time part, `TIMESTAMP [WITH TIME ZONE]`; booleans are not native before Oracle 23ai (use `NUMBER(1)` or `CHAR(1)`), PostgreSQL has `BOOLEAN`.
- Identity and sequences: sequence plus trigger (older), `GENERATED AS IDENTITY` (12c+) versus `SERIAL`/identity.
- Pagination: `OFFSET ... FETCH FIRST` (12c+, older ROWNUM) versus `LIMIT/OFFSET`.
- Upsert: `MERGE` versus `INSERT ... ON CONFLICT`.
- Date functions: `SYSDATE`, `SYSTIMESTAMP` versus `now()`, `CURRENT_TIMESTAMP`; interval arithmetic differs.
- Identifier length limits: 30 characters in older Oracle (128 from 12.2) versus 63 in PostgreSQL; case folding (Oracle uppercases unquoted identifiers, PostgreSQL lowercases).
- DDL transactionality: PostgreSQL supports transactional DDL (migrations roll back); Oracle DDL auto-commits, so partial migration failure needs manual cleanup.
- Concurrency: both MVCC, but Oracle undo-based reads and ORA-01555 "snapshot too old"; PostgreSQL vacuum and bloat.
- Locking and indexes: Oracle has no `INCLUDE` or partial indexes in the same form (function-based, bitmap indexes exist); unindexed foreign keys cause locking issues in Oracle.
- Plans: hints, SQL plan baselines, bind peeking versus PostgreSQL planner settings and generic/custom plans.

Mitigations: avoid vendor functions in JPQL, use Hibernate dialect abstractions, keep DDL minimal and portable (plain SQL, `TIMESTAMP`, `VARCHAR`), test migrations on the real target, and avoid relying on empty-string semantics.
**Follow-ups:**
- How would the empty-string difference break a unique constraint?
- How do you test Oracle compatibility without an Oracle server locally?
- What is transactional DDL and why does it matter for Flyway?
**Pitfalls:**
- Assuming SQL is portable by default.
- Ignoring empty string versus NULL.
- Booleans stored inconsistently.

## Data Stores, Connection Pools and Migrations

### Q89. How would you decide whether data belongs in Oracle, MySQL, or MongoDB?
**Tags:** Data Architecture, Staff, Scenario
**Analogy:** A filing cabinet with strict forms (relational), a flexible folder of loosely organized notes (document), and choosing between two brands of cabinet depends on what you must keep, search and audit.
**Model answer:**
Clarify per dataset: structure and rate of change of schema, consistency and transaction needs, query patterns (joins, aggregates, ad hoc), volume and growth, retention, availability, compliance and audit, existing team skills and licensing, and operational support.

Decision heuristics:
- Relational (Oracle or PostgreSQL or MySQL) for core transactional state with integrity: messages, lifecycle, idempotency, preferences, consent, audit. Needs: ACID, constraints (unique keys for dedupe), joins, optimistic locking, reporting. The platform's choice: PostgreSQL 16 (ADR-0005) with Oracle reference DDL (CONTRACT section 1), because the lifecycle and idempotency correctness depend on unique constraints and transactions.
- Oracle versus MySQL versus PostgreSQL: Oracle if the enterprise already operates it with DBAs, needs advanced partitioning, RAC, Data Guard, strong tooling and licensing is accepted; PostgreSQL for a feature-rich open source option (richer SQL, extensions, no license fees); MySQL (InnoDB) is strong for simpler workloads and mature replication, but historically weaker on complex SQL features, and has different default isolation (REPEATABLE READ with gap locks) and behaviors (e.g., silent truncation modes) to manage.
- Document store (MongoDB) for semi-structured, variable-shape, write-heavy, self-contained records read by key, with no multi-entity transactions required: raw provider callback payloads and diagnostics (`provider_diagnostics`, CONTRACT section 1), with TTL indexes for retention. Failure to write must never fail message processing; that is a sign it is a side store, not a source of truth.
- Not databases: Redis for ephemeral coordination, not truth; object storage for archives.

Anti-pattern: using MongoDB for money-like or consent state needing multi-document transactions and constraints, or forcing every payload variant into relational columns.

Measure: query latency, operational cost, incident history; revisit with data.
**Follow-ups:**
- Would you ever store message state in MongoDB?
- How do you migrate between engines?
- What are the consistency options in MongoDB?
**Pitfalls:**
- "NoSQL is faster."
- Choosing by familiarity alone.
- Splitting related transactional data across stores without a sync story.

### Q90. How would you model provider diagnostics and raw callback payloads in MongoDB, including retention?
**Tags:** MongoDB, Intermediate, Concept
**Analogy:** A box of raw receipts: you keep them for a while, in whatever shape they came, and throw them away automatically after the retention date.
**Model answer:**
Use case: store the raw provider callback or response (varying shapes per provider) for troubleshooting and audits, linked to a message id, without forcing a rigid schema (CONTRACT sections 1 and 2: `ProviderDiagnosticsStore`, `provider_diagnostics` collection).

Document design:
```json
{
  "_id": "ObjectId",
  "messageId": "msg-uuid",
  "tenantId": "tenant-retail",
  "providerId": "sim-email",
  "kind": "CALLBACK",
  "receivedAt": "2026-01-01T10:00:00Z",
  "payload": { "...provider specific, redacted..." },
  "schemaVersion": 1
}
```

Principles:
- Model for access patterns: lookup by `messageId` (and tenant), maybe by provider and time. Index `{messageId: 1}` and `{providerId: 1, receivedAt: -1}`.
- Retention: a TTL index on a date field (`expireAfterSeconds`) automatically deletes old documents (background task, not instantaneous).
- Redaction before storing: remove or mask PII and secrets (`common/Redaction`); raw payloads can contain addresses and phone numbers, so minimize; encrypt at rest and restrict access to `OPERATOR`/`PLATFORM_ADMIN` (CONTRACT section 5).
- Document size limit 16 MB; keep payloads small, bound the size.
- Write concern: for diagnostics, `w:1` is acceptable; failures are best-effort and counted in `messaging.diagnostics.write.failures`, never failing message processing.
- Tenant isolation: always filter by `tenantId`, enforce in the repository layer.
- Schema evolution: add `schemaVersion`; consumers handle missing fields.

Operations: monitor collection size and index size, use sharding only if needed (key by `messageId` hash). Authoritative delivery state remains in the relational `delivery_event` table with its dedupe constraint; Mongo is an archive.

Measure: write latency, failure counter, size growth, TTL deletion lag.

<!-- in-project:b35 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/diagnostics/ProviderDiagnosticsStore.java`
>
> Mongo holds only schema-flexible, expiring, non-critical documents; the message lifecycle stays in the relational database (ADR-0005). Locally an in-memory Mongo-compatible server is used (`MongoConfig`); real MongoDB was not executed.
>
> ```java
> /**
>  * Best-effort archive of raw provider callbacks and provider call summaries in MongoDB
>  * (provider_diagnostics). It exists for operators ("what exactly did the provider tell us?") and never takes
>  * part in the message flow: every write failure is swallowed and counted in
>  * messaging.diagnostics.write.failures. Payloads are redacted before they are stored, documents expire
>  * through a TTL index, and message content is never stored at all.
>  */
> public static final String COLLECTION = "provider_diagnostics";
> ```

**Follow-ups:**
- Why not store the diagnostics in a relational JSON column?
- How does the TTL monitor work?
- How do you handle PII in raw payloads?
**Pitfalls:**
- Storing raw PII indefinitely.
- No indexes, scanning on every lookup.
- Letting Mongo write failure break processing.

### Q91. How do MongoDB indexes and query plans work, and what is the ESR rule?
**Tags:** MongoDB, Senior, Concept
**Analogy:** Like a database index in a book: the order of the index terms determines which questions you can answer quickly.
**Model answer:**
MongoDB uses B-tree indexes. Single-field, compound, multikey (arrays), text, TTL, and hashed indexes exist. `explain("executionStats")` shows the winning plan (`IXSCAN` versus `COLLSCAN`), `nReturned`, `totalKeysExamined`, `totalDocsExamined`; the ideal ratio is examined close to returned.

Compound index design follows ESR: Equality fields first, then Sort fields, then Range fields. For `find({providerId:"p", messageId:"m"}).sort({receivedAt:-1})` an index `{providerId:1, messageId:1, receivedAt:-1}` serves filter and sort without an in-memory sort (which is limited to 100 MB by default and fails without `allowDiskUse`).

Other points:
- Prefix rule as in relational B-trees: `{a,b,c}` supports `a`, `a,b`, `a,b,c`.
- Covered queries: all fields in the index and projection excludes `_id` unless indexed, giving index-only reads.
- Index cardinality and write cost: each index slows writes and uses RAM; the working set of indexes should fit in memory.
- Multikey indexes on arrays can explode in size; compound indexes can include at most one array field.
- Collation, case sensitivity, and partial indexes (`partialFilterExpression`) are available in MongoDB (unlike the portable relational DDL used here).
- TTL indexes remove expired documents.
- Pagination: use range queries on an indexed key (keyset) rather than `skip` for deep pages.
- Transactions exist (multi-document, on replica sets) but cost performance; prefer single-document atomicity by embedding.
- Read and write concerns define consistency and durability: `w:majority`, `readConcern:majority` for stronger guarantees; `readPreference` secondary reads can be stale.

Measure: slow query log/profiler (`slowms`), index usage stats (`$indexStats`), working set versus RAM.
**Follow-ups:**
- Why is an in-memory sort problematic?
- When does an embedded document beat a reference?
- What does `w:majority` guarantee?
**Pitfalls:**
- Ignoring `explain`.
- Too many indexes.
- Deep `skip` pagination.

### Q92. API latency spikes and Hikari reports pending connections. How do you diagnose connection pool exhaustion?
**Tags:** SQL, Senior, Scenario
**Analogy:** A shop with a few shared tills: if some customers stand at the till chatting instead of paying, everyone else queues.
**Model answer:**
Clarify: when it started, which endpoints, whether traffic or deploys changed, and what pool size is configured. Metrics: `hikaricp_connections_active`, `_pending`, `_max`, `_usage_seconds`, `_acquire_seconds`.

Diagnosis:
- Long hold times: transactions that include slow work (remote calls to providers, large serialization, Open Session In View holding the connection through the response). Capture thread dumps: threads inside `HikariPool.getConnection` waiting, others holding connections inside business or HTTP calls.
- Slow queries: missing indexes or bad plans increase hold time. Check the slow query log and `pg_stat_activity` for long-running or idle-in-transaction sessions.
- Connection leaks: enable `leakDetectionThreshold` (for example 20 s) to log the stack trace of the borrowing code.
- Pool starvation by nesting: `REQUIRES_NEW` needs a second connection per request; parallel streams or executors using DB in the pool threads.
- Undersized or oversized pool: the pool should be small (often 10 to 30 per instance), because the database can only run as many queries concurrently as it has cores and I/O; formula-of-thumb `(core_count * 2) + effective_spindle_count`. Many instances multiply the connection count: total must stay under the DB's `max_connections` (consider PgBouncer).
- Lock waits: sessions blocked on row locks hold connections.

Fixes: shorten transactions (move provider calls outside), disable OSIV, add indexes, fix leaks, adopt timeouts (`connectionTimeout`, statement timeout, `idle_in_transaction_session_timeout`), and shed load via backpressure instead of queueing indefinitely.

Measure: p95 acquire time, active/pending, transaction duration, DB wait events.

<!-- in-project:b40 -->
> [!NOTE] In this project
> **File:** `backend/src/main/resources/application-prod-like.yml`
>
> A 5 second connection timeout turns pool exhaustion into a fast error (mapped to `503` + `Retry-After: 5` by the exception handler) instead of threads waiting forever. Budget the pool against instances x pool size versus the database connection limit.
>
> ```yaml
> spring:
>   datasource:
>     hikari:
>       maximum-pool-size: ${DB_POOL_SIZE:20}
>       connection-timeout: 5000
> ```

**Follow-ups:**
- Why not simply raise the pool size?
- What does `idle in transaction` indicate?
- How do you size across many instances?
**Pitfalls:**
- Increasing the pool until the DB falls over.
- Ignoring OSIV.
- No timeouts.

### Q93. How do you roll out a schema change with zero downtime using Flyway when old and new application versions run together?
**Tags:** SQL, Senior, Scenario
**Analogy:** Building a new bridge lane next to the old one and only closing the old lane after all traffic has moved.
**Model answer:**
Clarify: the change (rename column, add NOT NULL, split table), table size, rolling-deploy behavior (old and new pods overlap), and rollback requirements.

Use expand-and-contract (parallel change):
1. Expand: a backward-compatible migration, additive only: add the new column nullable or with a default, new table, new index (create indexes concurrently where supported to avoid long locks; in Oracle online index creation). Old code keeps working.
2. Deploy the application that writes both old and new (dual-write) and reads old or new with fallback.
3. Backfill existing rows in small batches with throttling (a Spring Batch job or batched `UPDATE` with limits) to avoid long locks and replication lag.
4. Switch reads to the new column; verify with checks.
5. Contract: after all instances run the new code and a safety period, drop the old column in a later migration.

Flyway practicalities (CONTRACT sections 1 and 4):
- Versioned migrations `V1__...` are immutable once applied: never edit; fix forward with a new version (checksums are validated).
- Migrations must run on H2 (PostgreSQL mode) and PostgreSQL, with Oracle reference DDL maintained separately; no vendor-only syntax in shared scripts.
- Seed data only under profiles `local,test` via locations extension; repeatable `R__` scripts for views.
- Run migrations as a separate step (init container or pipeline job) rather than at startup of many pods to avoid races (Flyway uses a lock but long migrations delay readiness).
- Big operations: adding a NOT NULL column with a default can rewrite the table on older PostgreSQL (11+ avoids it for constant defaults); use batching.
- Rollback: prefer forward fixes; keep the previous app version compatible with the expanded schema so an app rollback is safe.

Measure: migration duration, lock waits, replication lag during backfill, error rate during rollout.

<!-- in-project:b25 -->
> [!NOTE] In this project
> **File:** `backend/src/main/resources/application.yml`
>
> Flyway owns the schema and Hibernate only validates it (`ddl-auto: validate`). The migrations are plain portable SQL that runs on H2 and PostgreSQL; the Oracle reference DDL lives apart in `db/oracle/oracle_core_schema.sql` and was not extended for V200.
>
> ```yaml
> spring:
>   jpa:
>     open-in-view: false
>     hibernate:
>       ddl-auto: validate
>   flyway:
>     enabled: true
>     locations: classpath:db/migration
> # migrations: V1__tenant_recipient, V2__templates, V3__messages, V4__bulk_audit_idempotency_outbox,
> #             V50__dispatch_indexes, V100__spring_batch, V200__bulk_ledger_legal_hold
> ```

**Follow-ups:**
- Why are down migrations risky?
- How do you handle a failed Flyway migration on Oracle without transactional DDL?
- How do you backfill 500 million rows safely?
**Pitfalls:**
- Renaming a column in one step.
- Editing an applied migration.
- Long locking DDL during peak.

### Q94. After the nightly statistics job, a previously fast Oracle query now chooses a bad plan only for some tenants. What happened?
**Tags:** Oracle, Staff, Scenario
**Analogy:** A taxi dispatcher memorized the average city traffic, so on an unusual day the "best" route is a disaster for particular trips.
**Model answer:**
Clarify: Oracle version, whether bind variables are used, which tenants are affected, and what changed (statistics, data distribution, parameter, patch).

Likely causes:
- Plan instability due to new statistics: gathering stats on a skewed column (`tenant_id`) changes histograms, thus cardinality estimates and join methods.
- Bind peeking: the first execution after a hard parse peeks a bind value (a tiny tenant) and the resulting plan (nested loops, index) is reused for a huge tenant (and vice versa). Adaptive cursor sharing may mitigate it but not always.
- Data skew: one tenant holds most of the rows; a single plan cannot fit both.

Investigation:
- Compare plans: AWR/ASH reports, `DBMS_XPLAN.DISPLAY_CURSOR(sql_id, child_number, 'ALLSTATS LAST')`, `V$SQL`, check multiple child cursors and `V$SQL_PLAN`; find the plan hash value changes over time (`DBA_HIST_SQLSTAT`).
- Compare estimated versus actual rows.
- Check the statistics history (`DBA_TAB_STATS_HISTORY`).

Remediation (least invasive first):
1. Restore previous statistics (`DBMS_STATS.RESTORE_TABLE_STATS`) to stop the bleeding.
2. Gather statistics with proper histograms on skewed columns and lock stats for critical tables if required.
3. Use SQL Plan Management baselines to pin the known-good plan.
4. Rewrite the query or index to make the best plan obvious (composite index leading with tenant and status).
5. Avoid hints unless necessary.

Prevention: stats change review with test on production-like data, plan regression alerting, keep per-tenant data distribution in mind (consider partitioning by tenant range for giant tenants).

Measure: plan hash changes, elapsed time per execution by tenant, buffer gets.
**Follow-ups:**
- What is adaptive cursor sharing?
- When do you lock statistics?
- How would you partition by tenant?
**Pitfalls:**
- Disabling automatic statistics entirely.
- Hint-first culture.
- Ignoring skew.
