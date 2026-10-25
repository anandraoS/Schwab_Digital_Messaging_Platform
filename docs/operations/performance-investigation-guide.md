# Performance Investigation Guide

Concrete commands for investigating a running instance. Commands are standard JDK/Linux tools shown for reference; `<pid>` is the JVM process id (`jcmd -l` lists). **None of these were executed against a deployed instance in authoring; a local-run subset can be tried against the `local` profile (port 18080).** Heap dumps contain message variables and recipient data: handle as Confidential, restrict access, delete after use.

## 0. Method
1. Define the symptom with a metric (not "feels slow"). 2. Locate the bottleneck class: CPU, memory/GC, threads/locks, DB, queue/downstream, network. 3. Change one thing. 4. Verify with the same metric. 5. Record in the PIR.

## 1. First look (metrics)
| Question | Metric / endpoint |
|---|---|
| Request latency/error | `http_server_requests_seconds_{count,sum,bucket}` by `uri`,`status` |
| JVM heap | `jvm_memory_used_bytes{area="heap"}`, `jvm_memory_max_bytes` |
| GC | `jvm_gc_pause_seconds_{count,sum,max}`, `jvm_gc_live_data_size_bytes` |
| Threads | `jvm_threads_live_threads`, `jvm_threads_states_threads{state}`, `tomcat_threads_busy_threads` |
| CPU | `process_cpu_usage`, `system_cpu_usage` |
| DB pool | `hikaricp_connections_{active,idle,pending,max}`, `hikaricp_connections_acquire_seconds`, `hikaricp_connections_usage_seconds` |
| Queue | `messaging_queue_depth`, `messaging_queue_inflight`, `messaging_dispatch_delay_seconds`, `GET /api/v1/ops/queues` |
| Provider | `messaging_provider_latency_seconds`, `messaging_provider_calls_total{outcome}` |

## 2. CPU
```
top -H -p <pid>                       # per-thread CPU (Linux); note the hottest thread ids (decimal)
printf '%x\n' <tid>                   # convert to hex to match "nid=0x..." in jstack
jcmd <pid> Thread.print > threads.txt # find the thread by nid
jcmd <pid> JFR.start name=cpu settings=profile duration=60s filename=cpu.jfr
jfr print --events jdk.ExecutionSample cpu.jfr | head -200
jfr summary cpu.jfr
```
Open `cpu.jfr` in JDK Mission Control or IntelliJ profiler. Look for: JSON (de)serialization, regex catastrophic backtracking in validators, template rendering loops, logging (synchronous appenders), crypto (HMAC is cheap, RSA verify cached via JWT decoder). Windows equivalent: Task Manager/Process Explorer + same `jcmd` (JDK path: see CONTRACT section 0).

## 3. Memory and GC
```
jcmd <pid> GC.heap_info
jcmd <pid> GC.class_histogram | head -30       # suspects by instances/bytes
jcmd <pid> GC.heap_dump /secure/tmp/heap.hprof # STOP-THE-WORLD; PII risk
jstat -gcutil <pid> 1000 10                    # occupancy and GC counts every 1 s
jcmd <pid> VM.flags | tr ' ' '\n' | grep -E 'Xmx|Xms|UseG1|UseZGC|MaxRAMPercentage'
```
GC logging at startup (recommended in prod): `-Xlog:gc*:file=gc.log:time,uptime,level,tags:filecount=5,filesize=20m`. Read: pause times (G1 target 200 ms by default), allocation rate, "Pause Full" events (bad), humongous allocations (large byte arrays: e.g., unbounded CSV reads or large JSON bodies; we cap body at 256 KB and stream bulk CSV).
Typical findings and fixes:
| Finding | Likely cause | Fix |
|---|---|---|
| Heap grows steadily, old gen not reclaimed | unbounded cache, queue, per-request collections, `InMemoryMessageBus` queue too big (capacity 5000 by design) | bound it; inspect with histogram/MAT dominator tree |
| Frequent young GCs, high allocation | per-message large object creation, string concatenation, JSON trees | profile allocations (`jdk.ObjectAllocationSample` in JFR) |
| Container OOM-killed without Java OOM | native/off-heap/threads exceed limit | set `-XX:MaxRAMPercentage=70`, check metaspace/direct buffers (`jcmd <pid> VM.native_memory summary` with `-XX:NativeMemoryTracking=summary`) |

## 4. Threads and locks
```
jcmd <pid> Thread.print -l            # includes locked ownable synchronizers
jstack <pid>                          # same family; take 3 dumps 5-10 s apart and diff
jcmd <pid> Thread.print | grep -c 'HikariPool.getConnection'   # threads waiting for DB connections
jcmd <pid> Thread.dump_to_file -format=json threads.json       # JDK 21: also shows virtual threads
```
Patterns: many threads `WAITING` in `HikariPool.getConnection` => pool exhausted/slow DB; many `BLOCKED` on one monitor => contended lock; consumer workers all in provider socket read => slow provider with no effective timeout (check time limiter); `RUNNABLE` in regex/serialization => CPU hotspot.
Starvation checks: provider calls must use the dedicated bounded executor (not Tomcat threads, not the consumer pool itself); scheduler threads must not run long tasks; batch executor bounded.

## 5. Database
```sql
-- PostgreSQL (not executed here)
SELECT pid, state, wait_event_type, wait_event, now()-query_start AS runtime, left(query,100)
FROM pg_stat_activity WHERE state <> 'idle' ORDER BY runtime DESC;
SELECT query, calls, mean_exec_time, rows FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 10; -- needs extension
EXPLAIN (ANALYZE, BUFFERS) <slow query>;
SELECT relname, seq_scan, idx_scan, n_dead_tup FROM pg_stat_user_tables ORDER BY seq_scan DESC;
```
Application side: enable Hibernate statistics in a test and assert statement counts (N+1 detection), slow query log (`spring.jpa.properties.hibernate.session.events.log.LOG_QUERIES_SLOWER_THAN_MS=100`, verify property name for the Hibernate version in use). Pool: see `docs/database/transactions-and-connection-pooling.md`.

## 6. Queue lag
`GET /api/v1/ops/queues`; RabbitMQ: `rabbitmqctl list_queues name messages_ready messages_unacknowledged consumers` and management UI publish/deliver/ack rates. Rule: if `unacked` equals `consumers x prefetch` and ready grows, consumers are the bottleneck; if consumers idle and ready grows, check paused consumers, pollers (outbox) or prefetch misconfiguration. Little's law: throughput = in-flight / latency; with 8 workers and 400 ms provider latency max ~20 msgs/s per instance.

## 7. Network and downstream
Provider latency histograms; Resilience4j state (`messaging_provider_circuit_state`); DNS/TLS handshake time; connect vs read timeouts; retry amplification.

## 8. In-JVM load harness
`performance/jvm-harness` and `backend/src/test/.../perf/LoadHarnessIT` can be run locally; record numbers only from executed runs, with date and hardware (see `docs/validation/VALIDATION_REPORT.md` and `performance/README.md`).

## 9. Checklist artifact
`ai-engineering/checklists/performance-review.md`.
