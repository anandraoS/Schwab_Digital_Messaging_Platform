# Runbook: High API Latency, JVM GC, Thread Starvation, CPU Saturation

Detailed command reference: `../performance-investigation-guide.md`. This runbook is the triage decision tree.

## Symptoms and alerts
`http_server_requests_seconds` p95/p99 up for POST /messages; API availability burn; `jvm_gc_pause_seconds` high; CPU > 85%; `hikaricp_connections_pending` > 0; Tomcat busy threads at max.

## Triage tree
1. **Is it everywhere or one route?** Compare `http_server_requests_seconds` by `uri`. One route => slow query or downstream. All routes => resource saturation.
2. **DB pool?** `hikaricp_connections_pending > 0` or `acquire_seconds` high => `database-outage.md` (pool/lock/slow query).
3. **GC?** `jvm_gc_pause_seconds_max`, `jvm_memory_used_bytes{area="heap"}` near max, frequent full GCs => memory pressure.
4. **Threads?** `jvm_threads_live`, `tomcat_threads_busy`/ `executor` queue sizes; thread dump shows many threads BLOCKED/WAITING on the same lock or `HikariPool.getConnection`.
5. **CPU?** process CPU high with low GC => hot loop/regex/serialization/crypto (HMAC and JWT verify are cheap; JSON and rendering at volume can be hot); profile with JFR.
6. **Downstream?** `messaging.provider.latency`, Redis/Mongo latency (diagnostics write on request thread? it must not be).
7. **Noisy tenant?** `messaging.messages.accepted` by tenant; rate limit rejections.

## Immediate mitigation
| Finding | Action |
|---|---|
| Heap exhaustion | restart one instance at a time after capturing heap histogram/dump (`jcmd <pid> GC.class_histogram`, `jcmd <pid> GC.heap_dump <file>` store securely: heap contains PII-like data), increase memory only with justification |
| Thread starvation by blocking calls | confirm provider calls run on the dedicated bounded executor with timeouts; reduce `dispatch.concurrency` if downstream slow; check no provider calls on Tomcat threads |
| CPU | scale out replicas; capture JFR (`jcmd <pid> JFR.start duration=60s filename=...`) for root cause |
| Slow query | add index/kill query; see `query-plans-and-indexes.md` |
| Noisy tenant/bulk | throttle/stop bulk job; tighten tenant rate limit |
| Bad release | rollback (`../rollback-plan.md`) |

## Verify
p95/p99 back under SLO; pool pending 0; GC pause normal; no BLOCKED thread hot spots.

## Prevent
Load tests with GC/heap profile, timeouts everywhere, bounded pools, resource limits, perf review checklist (`ai-engineering/checklists/performance-review.md`).
