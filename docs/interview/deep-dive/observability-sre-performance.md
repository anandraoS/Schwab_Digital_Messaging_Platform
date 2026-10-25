# Observability, SRE, and Performance Deep Dive

Scope note: reference chapter for a senior Java full-stack messaging role interview. Metric names come verbatim from CONTRACT section 8 (`docs/design/CONTRACT.md`). Prometheus rules, dashboards, and k6 scripts in `observability/` and `performance/k6/` are statically validated only and NOT executed (CONTRACT sections 0 and 12). Numbers in examples are illustrative, not measurements. Record real measurements only if you executed them.

## The Analogy Corner

- **Three pillars = a doctor's visit.** Metrics are the thermometer and blood pressure (a number over time). Logs are the patient's diary (what happened, in words). Traces are an X-ray of one journey through the body (which organ was slow).
- **SLO and error budget = a school attendance goal.** "Be on time 99 days out of 100." That leaves 1 late day per 100. If you are late 3 times in the first week, you are burning the allowance too fast and should leave earlier (slow down releases).
- **Little's Law = a queue at the ice cream shop.** People in the shop equals arrival rate times how long each person stays. If scoopers get slower, the shop fills up even though customers arrive at the same pace.
- **Burn-rate alert = a bathtub filling.** You do not wait for it to overflow; you alarm when the tap is running fast enough to overflow before bedtime.

## Three Pillars and Their Limits

| Pillar | Strength | Weakness | Tooling in this design |
|---|---|---|---|
| Metrics | Cheap, aggregable, great for alerting and trends | No per-request detail; label cardinality limits | Micrometer, Prometheus registry |
| Logs | Rich context, searchable | Expensive at volume; unstructured is hard to query | Structured JSON, MDC |
| Traces | Cause of latency across services | Sampling, instrumentation effort | `micrometer-tracing-bridge-otel`, OTLP exporter (endpoint configurable, disabled locally per CONTRACT section 1) |

Observability is the ability to ask new questions of a running system without shipping new code. The pillars are inputs; the goal is fast diagnosis. Add **events** (audit and delivery events) and **profiles** (async-profiler, JFR) as practical fourth and fifth signals.

## Micrometer, Prometheus, OpenTelemetry

- **Micrometer** is the vendor-neutral metrics facade in Spring Boot. `MeterRegistry` creates `Counter`, `Gauge`, `Timer`, `DistributionSummary`. All custom meters live in the `metrics/MessagingMetrics` class (CONTRACT section 2).
- **Prometheus** pulls from `/actuator/prometheus` (permitted locally, network-restricted in production per CONTRACT section 5). Name conversion: dots become underscores and units are suffixed, so `messaging.dispatch.delay` appears as `messaging_dispatch_delay_seconds_*`; counters get `_total`. Always check the actual exposition output when writing PromQL.
- **OpenTelemetry** standardises traces, metrics, and logs and the OTLP wire protocol. In Spring Boot 3.x, Micrometer Tracing with the OTel bridge produces spans and propagates W3C `traceparent`. Correlate logs and traces by putting the trace id in MDC.

Timers can publish percentile histograms (`publishPercentileHistogram`) so Prometheus can compute `histogram_quantile` across instances. Client-side percentiles cannot be aggregated across pods; histogram buckets can.

### Cardinality rules

A label with unbounded values (message id, recipient, correlation id) multiplies time series and can take down Prometheus. Allowed labels in the contract are small, bounded sets: `channel`, `priority`, `provider`, `outcome`, `status`, `reason`, `result`. Two things to watch:

- `messaging.messages.accepted{channel,priority,tenant}` includes `tenant`: acceptable while tenant count is modest; with thousands of tenants, drop or bucket it.
- `messaging.bulk.progress{jobId}` is a gauge keyed by job id: remove the meter when a job completes, otherwise series accumulate.

## The Platform Metric Catalogue (CONTRACT section 8)

| Metric | Type | Labels | What it tells you |
|---|---|---|---|
| `messaging.messages.accepted` | counter | channel, priority, tenant | Ingress rate |
| `messaging.transitions` | counter | from, to | State machine flow; derive status distribution and failure ratios |
| `messaging.dispatch.delay` | timer | none | Accepted to provider-submit latency, the core user-facing SLI for async delivery |
| `messaging.provider.calls` | counter | provider, channel, outcome | Provider success, failure, and timeout rates |
| `messaging.provider.latency` | timer | provider | Provider call latency |
| `messaging.provider.circuit.state` | gauge | provider | 0 closed, 1 half-open, 2 open |
| `messaging.queue.depth` | gauge | priority | Backlog per priority |
| `messaging.queue.inflight` | gauge | none | Messages being processed |
| `messaging.queue.dlq.depth` | gauge | none | Dead letters awaiting operator action |
| `messaging.retry.scheduled` | counter | channel | Retry volume |
| `messaging.deadletter.total` | counter | reason | Dead-lettering by reason (`RETRIES_EXHAUSTED`, `POISON_MESSAGE`, `PERMANENT_FAILURE`, `OPERATOR`) |
| `messaging.callbacks` | counter | result | accepted, duplicate, invalid_signature, replayed, out_of_order, stale |
| `messaging.bulk.progress` | gauge | jobId | Bulk job progress |
| `messaging.idempotency.hits` | counter | none | Duplicate submissions absorbed |
| `messaging.ratelimit.rejected` | counter | scope | 429s by scope |
| `messaging.redis.fallback` | counter | none | Redis outage fallback activations |
| `messaging.diagnostics.write.failures` | counter | none | Best-effort Mongo write failures (must never fail processing) |

Also available from Spring and the JVM: `hikaricp_connections_*`, `http_server_requests_seconds`, `jvm_*`, `process_*`.

Illustrative PromQL (verify exposition names before use):

```promql
# Provider failure ratio, 5m
sum(rate(messaging_provider_calls_total{outcome!="ACCEPTED"}[5m])) by (provider)
/
sum(rate(messaging_provider_calls_total[5m])) by (provider)

# p95 dispatch delay
histogram_quantile(0.95, sum(rate(messaging_dispatch_delay_seconds_bucket[5m])) by (le))

# API error ratio
sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
/ sum(rate(http_server_requests_seconds_count[5m]))
```

## Golden Signals Applied

| Signal | API tier | Consumer and provider tier |
|---|---|---|
| Latency | `http_server_requests_seconds` p95/p99 for `POST /api/v1/messages` | `messaging.dispatch.delay`, `messaging.provider.latency` |
| Traffic | `messaging.messages.accepted` rate | Dispatch rate from `messaging.transitions{to="SENT_TO_PROVIDER"}` |
| Errors | 5xx ratio; 429 via `messaging.ratelimit.rejected` | Provider non-ACCEPTED outcomes; `messaging.deadletter.total` |
| Saturation | Hikari pending threads, Tomcat threads | `messaging.queue.depth`, oldest-message age (from `/api/v1/ops/queues`), thread pool queue, circuit state |

The USE method (utilisation, saturation, errors) is the resource-level companion; RED (rate, errors, duration) is the request-level companion.

## SLIs, SLOs, and Error Budgets

Messaging has two kinds of promises: synchronous (accept the request) and asynchronous (get the message to the provider, and eventually delivered). Delivery to a handset is partly outside your control, so separate what you own.

| SLI | Definition | Example SLO (illustrative) |
|---|---|---|
| API availability | Non-5xx / total on `POST /api/v1/messages` | 99.9% over 30 days |
| API latency | Fraction of accepts under 300 ms | 99% |
| Dispatch freshness | Fraction of HIGH/CRITICAL messages submitted to provider within 30 s of acceptance | 99% |
| Provider hand-off success | ACCEPTED / (calls excluding recipient-caused permanent failures) | 99.5% |
| Callback processing | Valid callbacks applied or deduped successfully | 99.9% |

Error budget arithmetic: a 99.9% SLO over 30 days allows 0.1% of 43,200 minutes, about 43 minutes of full unavailability, or the equivalent in bad requests. Spend the budget on releases and experiments; when it is exhausted, prioritise reliability work.

Design SLIs per priority: CRITICAL security alerts deserve a tighter freshness target than LOW marketing. Do not blend them into one average.

### Burn-rate alerts (multi-window, multi-burn-rate)

Burn rate = observed error ratio divided by the budget ratio (1 - SLO). Burn rate 1 spends the budget exactly over the window. Typical pairs (from the Google SRE workbook):

| Severity | Burn rate | Long window | Short window | Budget consumed |
|---|---|---|---|---|
| Page | 14.4 | 1 h | 5 min | 2% |
| Page | 6 | 6 h | 30 min | 5% |
| Ticket | 1 | 3 days | 6 h | 10% |

The short window confirms the problem is still happening, which cuts false alerts and speeds resolution.

```yaml
# Illustrative alert rule, 99.9% SLO => budget 0.001
- alert: ApiErrorBudgetFastBurn
  expr: |
    (sum(rate(http_server_requests_seconds_count{status=~"5.."}[1h]))
       / sum(rate(http_server_requests_seconds_count[1h])) > (14.4 * 0.001))
    and
    (sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
       / sum(rate(http_server_requests_seconds_count[5m])) > (14.4 * 0.001))
  labels: { severity: page }
```

Alert on symptoms (user-visible SLIs) for paging and use cause-based signals (queue depth, circuit open, DLQ growth) as ticket-level or dashboard items. Every page needs a runbook link (`docs/operations/runbooks`).

## Structured Logs and MDC

CONTRACT section 1: JSON logs (Spring Boot 3.4+ structured logging is acceptable) with MDC keys `correlationId`, `tenant`, `messageId`. `CorrelationIdFilter` accepts or generates `X-Correlation-Id` and echoes it (CONTRACT section 5).

```java
// Illustrative
MDC.put("correlationId", correlationId);
MDC.put("tenant", tenantId);
try {
    chain.doFilter(request, response);
} finally {
    MDC.clear();   // thread pools reuse threads
}
```

Key points:

- MDC is thread-local. When work hops to an executor, copy the context (task decorator) or logs lose correlation. For queue consumers, restore MDC from the `DispatchEnvelope` (`correlationId`, `tenantId`, `messageId` are in it) and clear in `finally`.
- One event per line, stable field names, no PII (see the security chapter), log at the decision point (state transition, provider outcome), not every loop.
- Levels: ERROR means someone should look; expected business outcomes (suppressed, opted out) are INFO or metrics, not errors.
- Sampling and rate-limit noisy logs; keep audit as a database record, not just a log line.

<!-- in-project:d08 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/common/CorrelationIdFilter.java`
>
> MDC is thread-local, so it is cleared in `finally` on every request thread and re-populated by `DispatchConsumer.handle` on consumer threads (`correlationId`, `messageId`, `tenant`).
>
> ```java
> } finally {
>     MDC.remove(MDC_KEY);
>     MDC.remove("tenant");
>     MDC.remove("messageId");
> }
> ```


## Troubleshooting Playbooks

General method: (1) confirm impact with SLIs, (2) find what changed, (3) localise with golden signals and traces, (4) mitigate first, (5) fix and write up.

### High CPU

1. Check `process_cpu_usage`, per-pod spread, and throttling (container CPU limits cause throttling that looks like latency).
2. Take thread dumps (`jcmd <pid> Thread.print`, three samples) and a CPU profile (JFR or async-profiler).
3. Common causes: regex backtracking, JSON or template rendering hot loops, tight retry loops without backoff, excessive logging, GC churn disguised as CPU.
4. Mitigate: scale out, reduce consumer concurrency, roll back a recent change.

### GC problems

1. Symptoms: latency spikes aligned with pauses, `jvm_gc_pause_seconds`, rising `jvm_memory_used_bytes` after GC (leak) versus sawtooth (healthy).
2. Enable GC logging (`-Xlog:gc*`); inspect allocation rate and promotion.
3. Causes: oversized batches or result sets (bulk jobs should stream, CONTRACT section 7), unbounded caches, large payloads held in memory, heap too small relative to limit.
4. Heap dump (`jcmd <pid> GC.heap_dump`) only with care: it contains data, so treat as sensitive.
5. Java 21 defaults to G1; consider ZGC for strict pause targets. Measure before changing.

### Thread starvation

1. Symptoms: latency high but CPU low, requests queued, executor queue growing, many threads in WAITING or BLOCKED.
2. Look at thread dumps for blocked-on-the-same-lock stacks or all workers waiting on a provider call.
3. Platform factors: consumer pool is bounded (`consumer.concurrency`, default 8). A slow provider can occupy every worker. The time limiter and circuit breaker (Resilience4j) exist to free threads quickly; verify `messaging.provider.latency` and `messaging.provider.circuit.state`.
4. Fix: tighten timeouts, bulkhead per provider, avoid blocking calls inside shared pools, never use an unbounded queue in front of a bounded pool without a rejection policy.

### Database connection pool exhaustion

1. Symptoms: `hikaricp_connections_pending` greater than zero, `hikaricp_connections_active` at max, timeouts acquiring connections, API latency up.
2. Causes: long transactions (holding a connection during a provider call is the classic bug; the design persists attempts around the call, not inside one long transaction), N+1 queries (repositories use fetch joins and `@EntityGraph`, CONTRACT section 2), missing indexes, too many replicas times pool size exceeding database `max_connections`.
3. Arithmetic: total connections = replicas x pool size per replica plus batch jobs; check it against PostgreSQL limits, or use a pooler such as PgBouncer.
4. Diagnose with `pg_stat_activity` for idle in transaction and long-running queries; enable slow query logging.
5. Fix before raising the pool: shorten transactions, fix queries, add indexes. A larger pool often makes a saturated database slower.

### Queue lag

1. Symptoms: `messaging.queue.depth` rising, `messaging.dispatch.delay` p95 rising, oldest-message age growing.
2. Decide: arrival rate above service rate, or the consumers stalled?
   - Stalled: consumers paused (`/ops/consumers/pause`), unacked messages stuck, Rabbit connection issue, poison message loop.
   - Slow: provider latency up, rate limiter (token bucket) throttling, DB saturation.
   - Surge: bulk job or campaign flooding, lower priorities starving or flooding.
3. Check `messaging.provider.calls` outcomes, `messaging.retry.scheduled` growth (retry storms), `messaging.queue.dlq.depth`, `messaging.ratelimit.rejected`.
4. Mitigate: scale consumers if the provider limit allows, throttle bulk jobs (`rate_limit_per_sec`), pause low-priority work, fail over to a secondary provider by configuration, apply backpressure (503 with `Retry-After` at `messaging.backpressure.max-queue-depth`).
5. Priority semantics: CRITICAL and HIGH should drain first (x-max-priority 9 in RabbitMQ). Verify low priority is not entirely starved if that matters to the business.

### Retry storm and provider outage

Backoff with jitter (CONTRACT section 6), circuit breaker open state, and retry caps keep the platform from hammering a failing provider. Watch `messaging.retry.scheduled` and `messaging.provider.circuit.state`. When the provider returns, expect a thundering herd; the token bucket smooths it.

## Little's Law and Capacity Planning

L = lambda x W: average items in the system equals arrival rate times average time in system.

Worked example (illustrative numbers):

- Provider call latency W = 0.4 s average, target throughput lambda = 200 messages/s.
- Required concurrent in-flight calls L = 200 x 0.4 = 80.
- With 8 workers per pod, you need at least 10 pods, ignoring headroom. Add 30 to 50 percent headroom for variance and failures, so roughly 13 to 15 pods.
- If provider latency doubles to 0.8 s, the same pool now supports only half the throughput. This is why latency spikes cause queue lag even when traffic is unchanged.

Use it for connection pools too: 100 requests/s at 50 ms average DB time needs about 5 busy connections on average; tails and bursts are why pools are sized several times larger than the mean.

Utilisation caution: queueing delay grows sharply above roughly 70 to 80 percent utilisation (M/M/1 intuition). Plan capacity for peaks, not averages.

## Load Testing Methodology

Tools: k6 scripts live in `performance/k6/` (not executed here); a Java in-JVM harness can be run locally (CONTRACT section 1). Report only what you ran.

Method:

1. **Define goals** from SLOs: for example, p95 accept latency, sustained rate, dispatch delay under load.
2. **Model the workload**: mix of channels and priorities, realistic payload sizes, idempotency keys that are unique per request (reusing a key tests the replay path, not the write path), bulk jobs concurrent with interactive traffic.
3. **Isolate the system under test**: simulators or stubs for providers with realistic latency and failure rates; never load test a real provider or real recipients.
4. **Test types**: smoke, load (expected peak), stress (find the break point), soak (hours, finds leaks), spike (sudden surge), and breakpoint tests.
5. **Open versus closed models**: closed models (fixed virtual users) hide overload because slow responses reduce arrivals (coordinated omission). Prefer arrival-rate executors for services exposed to independent clients.
6. **Measure end to end**: accepted-to-submitted delay requires observing the async pipeline, not only the HTTP response time.
7. **Watch the whole stack**: CPU, GC, Hikari, queue depth, provider limiter, database. Find the first saturated resource; that is your bottleneck.
8. **Change one thing at a time**, repeat runs for variance, record environment and versions.
9. **Warm up** the JVM (JIT, caches) and discard warm-up data.
10. **Report honestly**: tool, command, environment, run count, percentile definitions, and what was NOT tested.

Illustrative k6 shape (not executed):

```javascript
export const options = {
  scenarios: {
    submit: {
      executor: 'constant-arrival-rate',
      rate: 50, timeUnit: '1s', duration: '5m',
      preAllocatedVUs: 50, maxVUs: 200,
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<300'],
    http_req_failed: ['rate<0.001'],
  },
};
```

## Interview Q and A Snippets

**Q: Why histograms instead of averages?** Averages hide tails; users feel p99. Histogram buckets aggregate across pods; precomputed client percentiles do not.

**Q: What would you page on for this platform?** Fast burn of API availability, dispatch freshness for CRITICAL and HIGH, DLQ growth with reason `POISON_MESSAGE`, and an open circuit on the only provider for a channel. Queue depth alone is a ticket unless it threatens the freshness SLO.

**Q: Latency is high, CPU is low, what now?** Waiting, not computing: thread dump for blocked threads, Hikari pending, provider latency, lock contention, or downstream timeouts.

**Q: How do you avoid alert fatigue?** Alert on SLO burn, require runbooks, use multi-window rules, review noisy alerts in postmortems, and delete alerts nobody acts on.

## Key takeaways

- Metrics for alerting, logs for context, traces for latency localisation; correlate with trace id and `correlationId`.
- Use the exact CONTRACT section 8 metric names; remember Prometheus renames dots, adds unit suffixes, and `_total`.
- Keep labels bounded; remove per-job gauges when jobs end.
- Define SLIs per priority and per tier (accept versus dispatch versus delivery) and alert on multi-window burn rate, not raw thresholds.
- MDC is thread-local: propagate through executors and restore from the envelope in consumers; always clear.
- Playbooks: CPU needs profiles, GC needs allocation analysis, starvation needs thread dumps, pool exhaustion needs shorter transactions before bigger pools, queue lag needs "stalled versus slow versus surge".
- Little's Law (L = lambda x W) converts latency and throughput targets into worker and connection counts.
- Load test with arrival-rate models, simulated providers, warm-up, and honest reporting; claim only what was executed.
