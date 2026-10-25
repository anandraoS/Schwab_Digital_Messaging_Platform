# Question Bank 6: Performance Troubleshooting, Observability / SRE and AI-Assisted Engineering (Q119-Q142)

Reference-implementation study material. Metric names cite CONTRACT section 8. Any performance numbers in these answers are illustrative arithmetic, not measurements from this project unless explicitly recorded in the validation report.

## Performance

### Q119. How would you investigate a growing messaging queue backlog?
**Tags:** Performance, Senior, Scenario
**Analogy:** A growing line at a shop: you check whether more customers arrived, the cashiers slowed down, a cashier left, or the card machine broke.
**Model answer:**
Clarify: which queue and priority, since when, how fast it grows, and whether message age (oldest message) or only depth is rising. Depth is a count; age tells you the customer impact.

Backlog math: growth rate = arrival rate minus processing rate. Investigate each side.

Step 1: Arrival side. Check `messaging.messages.accepted{channel,priority,tenant}` for a spike: a campaign, a retry storm (look at `messaging.retry.scheduled`), a client replaying, or duplicate publishing from the outbox.

Step 2: Consumer side. Are consumers alive and connected (consumers count in `GET /ops/queues`, CONTRACT section 5)? Were they paused (`/ops/consumers/pause`)? Pods restarting or OOMKilled? In-flight versus prefetch: `messaging.queue.inflight`.

Step 3: Where the time goes per message. `messaging.provider.latency{provider}` (p95 up means slow provider), `messaging.provider.calls{outcome}` (rising TIMEOUT or RATE_LIMITED), circuit state `messaging.provider.circuit.state`, token-bucket exhaustion (requeue with delay without consuming attempts), database (Hikari pending, slow queries), thread dumps showing blocked workers.

Step 4: Poison or stuck messages: DLQ depth and redeliveries; `PROCESSING` rows with stale locks.

Step 5: Downstream capacity ceilings: the provider rate limit caps throughput regardless of consumers; more consumers will not help.

Actions by cause: provider slow, enable failover or reduce concurrency and tune timeouts; consumer capacity, scale replicas (watching DB pool and provider limits); campaign flooding, throttle or defer bulk and prioritize critical messages; poison messages, DLQ them; DB bottleneck, fix queries or pool.

Communicate: estimated time to drain = backlog / (processing rate minus arrival rate). Mitigate customer impact by expiring stale messages and prioritizing.

Measure: depth, oldest age, drain rate, `messaging.dispatch.delay` p95.
**Follow-ups:**
- What alert would you set on queue health?
- Why is oldest-message age better than depth?
- How do you tell a slow provider from a slow database?
**Pitfalls:**
- Scaling consumers blindly.
- Looking at depth alone.
- Ignoring priority and expiry.

### Q120. API pods show sustained 90 percent CPU and latency is rising. How do you find the cause?
**Tags:** Performance, Senior, Scenario
**Analogy:** A busy kitchen: you find which dish is eating the cooks' time before hiring more cooks.
**Model answer:**
Clarify: when it started, whether traffic increased (RPS by endpoint), recent deploys, and whether CPU is user or system time, and whether it is all pods or some.

Approach:
1. Correlate: `http_server_requests_seconds` by URI and status, request rate, GC activity (`jvm_gc_pause_seconds`), thread counts. A traffic spike differs from a regression.
2. Profile instead of guessing: take a CPU profile with async-profiler or Java Flight Recorder (`jcmd <pid> JFR.start duration=60s filename=...`), or thread dumps sampled several times (`jstack`); look at the hottest frames: JSON serialization of large responses, regex backtracking, per-request template compilation, excessive logging (string formatting at DEBUG), crypto (HMAC/JWT verification without key caching), N+1 queries serialization, reflection.
3. GC overhead: if GC threads dominate CPU, tune allocation (reduce garbage) or heap size rather than code CPU.
4. Container effects: CPU limits cause throttling (`container_cpu_cfs_throttled_seconds_total`); high CPU percent of a low limit might be normal; JIT compilation on startup consumes CPU.
5. Contention: many threads runnable on few cores, rate limiter spin, lock contention (blocked threads in dumps).

Typical fixes: cache JWKS and compiled templates, pre-compute, paginate or project instead of serializing entities, fix hot loops, tune logging, add indexes if DB wait is mistaken for CPU (DB CPU is separate), scale horizontally after identifying the real limit.

Validate with a before/after load test at the same request mix; keep profiles as evidence. Do not tune with unrelated flags.

Measure: CPU per request (CPU seconds / requests), p95/p99, GC time percentage, throttling ratio.
**Follow-ups:**
- Why use a sampling profiler?
- How do you tell throttling from saturation?
- What if CPU is high but threads are mostly waiting?
**Pitfalls:**
- Scaling out without profiling.
- Debug logging in production.
- Ignoring CPU throttling.

### Q121. The p99 latency of the accept endpoint spikes every few minutes while the average is fine. How do you find the cause?
**Tags:** Performance, Senior, Scenario
**Analogy:** A bus that is usually on time but occasionally stops for ten minutes: the average hides the stop, so you look at when and why it stops.
**Model answer:**
Clarify: spike period and duration, which endpoints, correlated with deploys, schedulers, or load.

Hypotheses, by periodic pattern:
- GC pauses: check GC logs for pause times (`-Xlog:gc*`); a full GC or long mixed collections every N minutes. Fix: heap sizing, reduce allocation or humongous objects, G1 tuning or ZGC.
- Scheduled jobs: `OutboxPublisher` (every 200 ms), `ScheduledDispatchPoller`, `StuckMessageSweeper`, `RetentionJob`, or batch jobs hitting the same database and causing lock waits or I/O saturation (CONTRACT section 2). Fix: smaller batches, off-peak scheduling, indexes, separate pools.
- Database: checkpoint or autovacuum activity, lock waits, plan flips; check `pg_stat_activity`, waiting queries, `pg_stat_bgwriter`.
- Connection pool: Hikari pending spikes when a burst arrives; JVM thread contention.
- Infrastructure: noisy neighbor, CPU throttling (CFS quota) in bursts, network blips, DNS TTL expiry, TLS handshakes, JWKS refresh, cache expiry stampede (many requests refresh at once; use single-flight/jittered TTL).
- Downstream: the Redis fallback path or Mongo diagnostic writes if they run on the request path (they should not).

Method: overlay timelines: latency histogram heatmap (not just average), GC pause metric, pool pending, DB wait, scheduler execution times. Use distributed traces of slow requests: the slow span shows where the time went. Take a JFR recording that covers a spike.

Fix the cause, then add an SLO-based alert on p99 and a dashboard panel with the correlated signals. Report percentiles (p50, p95, p99), not averages.

Measure: p99 over time, GC pause max, scheduler duration, DB lock wait.
**Follow-ups:**
- Why is the average misleading?
- What is coordinated omission in load tests?
- How do exemplars link metrics to traces?
**Pitfalls:**
- Staring at averages.
- Guessing GC without logs.
- Forgetting that background jobs share resources.

### Q122. Explain Little's Law and use it to size capacity for the dispatch consumers.
**Tags:** Performance, Intermediate, Concept
**Analogy:** If 10 customers arrive per minute and each stays 3 minutes in the shop, there are on average 30 people inside.
**Model answer:**
Little's Law: L = lambda x W, where L is the average number of items in the system, lambda is the arrival (throughput) rate, W is the average time each item spends in the system. It holds for any stable system, regardless of distribution.

Applications:
- Concurrency needed: if provider calls average 200 ms and you need 500 sends per second, then L = 500 x 0.2 = 100 concurrent calls (threads or in-flight requests). With 8 workers (the default consumer concurrency, CONTRACT section 9), maximum throughput is 8 / 0.2 = 40 per second per instance; hence either more workers, more instances, or virtual threads with a semaphore.
- Queue sizing: if the arrival rate exceeds the service rate, the queue grows without bound; stability requires utilization below 1 (arrival rate under capacity). Waiting time increases sharply near full utilization (queueing theory: wait is proportional to utilization / (1 - utilization)); target 60 to 70 percent utilization for headroom.
- Database pool: if each request holds a connection for 20 ms and the target is 1,000 RPS, L = 1,000 x 0.02 = 20 connections busy on average; add margin for variance and spikes.
- Drain time: backlog B with processing capacity mu and arrival lambda: time = B / (mu - lambda). With 120,000 backlog, capacity 500/s and arrivals 300/s: 120,000 / 200 = 600 seconds.

Cautions: averages hide tail latency; use percentile-based worst cases for timeouts and pool limits. Capacity must be limited by the narrowest resource (provider limit, database, threads).

Use it to cross-check dashboards: in-flight (`messaging.queue.inflight`) should approximate throughput times provider latency.

Measure: throughput, latency, in-flight, utilization.
**Follow-ups:**
- Why does latency explode near 100 percent utilization?
- How does this guide HPA settings?
- How do you size the Hikari pool?
**Pitfalls:**
- Using average latency for sizing under high variance.
- Ignoring the bottleneck resource.
- Running at 95 percent utilization.

### Q123. How would you run a credible load test for the messaging API, and what mistakes make results meaningless?
**Tags:** Performance, Senior, Concept
**Analogy:** Testing a bridge with realistic traffic and weights, in the right conditions, and reporting what you measured honestly.
**Model answer:**
Define goals first: target throughput, latency SLO (for example p99 under 300 ms at 500 requests per second sustained), duration, and the success criteria; then the workload model: realistic mix of endpoints, payload sizes, tenants, idempotency keys, ratio of reads to writes, plus background consumer load.

Method:
- Test environment parity: same instance sizes, same database engine and version (real PostgreSQL, RabbitMQ, Redis, not H2 and in-memory substitutes, which the local profile uses for convenience only), similar data volume (millions of rows, since query plans change with size), network topology.
- Tools: k6, Gatling, JMeter; the project has k6 scripts (not executed here) and a Java in-JVM harness (CONTRACT section 1); only measurements actually executed may be recorded.
- Open versus closed workload models: an open model (fixed arrival rate) reveals queueing; closed models (fixed users) self-throttle when the system slows, causing coordinated omission and understating latency. Use constant-arrival-rate executors.
- Warm up (JIT, caches, pools), then steady state; ramp, stress, spike and soak (memory leaks, connection leaks) tests.
- Isolate the system under test: stub providers with the simulator and realistic latency and fault distributions; never call real providers.
- Observe everything: CPU, GC, pool metrics, DB wait events, queue depth, error rates, with the load generator not being the bottleneck (monitor its CPU).
- Report percentiles, error rates, throughput, resource use, and the limiting resource; include configuration and versions for reproducibility; repeat runs for variance.

Mistakes: testing on a laptop with H2 and claiming production capacity, averages instead of percentiles, no warm-up, unrealistic data (one tenant, same payload), ignoring errors counted as fast responses, and extrapolating linearly.

Measure: max sustainable throughput at SLO, saturation point, scaling efficiency.

<!-- in-project:b37 -->
> [!NOTE] In this project
> **File:** `performance/k6/backlog-recovery.js`
>
> Eight k6 scenarios exist under `performance/k6/` (submit-high-volume, retry-storm, provider-rate-limit, bulk-processing, ...). k6 was not installed, so they were never run and this guide quotes no throughput or latency measurement from them.
>
> ```javascript
> // Scenario: pause consumers, build a backlog, resume, measure drain time. Needs PLATFORM_ADMIN (dev token). NOT executed.
> export const options = {
>   scenarios: { recovery: { executor: 'per-vu-iterations', vus: 1, iterations: 1, maxDuration: '30m' } },
>   thresholds: { mp_backlog_drain_seconds: ['p(95)<600'], checks: ['rate>0.99'] },
> };
> ```

**Follow-ups:**
- What is coordinated omission?
- How do you load test with provider simulators realistically?
- What does a soak test catch?
**Pitfalls:**
- Quoting numbers from a non-representative environment.
- Closed-model tests only.
- Counting fast failures as good latency.

## SRE and Observability

### Q124. Define SLIs and SLOs for a messaging platform. What would you choose and why?
**Tags:** SRE, Senior, Concept
**Analogy:** A restaurant promise: "95 percent of orders served within 15 minutes"; the SLI is the measured percentage, the SLO is the promise.
**Model answer:**
SLI: a measured indicator of service level (a ratio of good events to valid events). SLO: the target for that SLI over a window (for example 99.9 percent over 30 days). SLA: contractual consequences, usually looser than the SLO.

Choose user-centric SLIs for each critical journey:
- Accept availability: proportion of `POST /messages` requests not returning 5xx (excluding client errors and intentional 429/503 backpressure, or counting 503 depending on policy) measured via `http_server_requests_seconds`. SLO: 99.95 percent.
- Accept latency: proportion of accepts under 300 ms. SLO: 99 percent.
- Dispatch timeliness: proportion of transactional messages reaching the provider within 30 seconds of acceptance (`messaging.dispatch.delay` histogram). SLO: 99 percent. For campaigns, a looser window.
- Delivery success: proportion of provider-accepted messages that reach `DELIVERED` within N minutes, excluding hard bounces and suppressed recipients (bad addresses are not our failure). This depends on providers; set it as an SLO per provider and monitor rather than promise absolutely.
- Freshness of status: callback processing delay.
- Correctness: duplicate send rate and lost message count (target zero; measure via reconciliation).

Principles: few SLIs per service, measured at the boundary, excluding noise; separate transactional versus marketing classes; avoid vanity metrics (CPU); define valid events clearly (do not count suppressed messages as failures). Error budget = 1 minus SLO: 99.9 percent over 30 days allows about 43 minutes of full failure.

Implementation: Prometheus recording rules for SLIs, Grafana dashboards, burn-rate alerts (Q125). Review SLOs quarterly with product input.

Measure: SLO attainment, budget remaining.
**Follow-ups:**
- Why exclude hard bounces from delivery SLOs?
- How do you set a realistic target?
- Who owns the SLO?
**Pitfalls:**
- Promising 100 percent.
- Using infrastructure metrics as SLIs.
- One SLO for all message classes.

### Q125. What are error budgets and multi-window burn-rate alerts, and how would you alert on the accept API?
**Tags:** SRE, Senior, Concept
**Analogy:** A monthly spending allowance: spending fast triggers an alarm immediately, spending slowly triggers a gentler warning.
**Model answer:**
Error budget = allowed unreliability = (1 - SLO) x window. For a 99.9 percent monthly SLO, the budget is 0.1 percent of requests (about 43 minutes of full outage). Burn rate = observed error rate / allowed error rate; burn rate 1 spends exactly the budget over the window; 14.4 spends 2 percent of a 30-day budget in one hour.

Multi-window multi-burn-rate alerting (from SRE practice):
- Page: burn rate above 14.4 over 1 hour AND over 5 minutes (short window confirms it is still happening); budget exhaustion in about 2 days.
- Page (slower): burn rate above 6 over 6 hours AND over 30 minutes.
- Ticket: burn rate above 1 over 3 days AND over 6 hours.

Prometheus sketch (illustrative):
```yaml
- alert: AcceptApiFastBurn
  expr: |
    (sum(rate(http_server_requests_seconds_count{uri="/api/v1/messages",status=~"5.."}[1h]))
     / sum(rate(http_server_requests_seconds_count{uri="/api/v1/messages"}[1h]))) > (14.4 * 0.001)
    and
    (sum(rate(http_server_requests_seconds_count{uri="/api/v1/messages",status=~"5.."}[5m]))
     / sum(rate(http_server_requests_seconds_count{uri="/api/v1/messages"}[5m]))) > (14.4 * 0.001)
  for: 2m
  labels: { severity: page }
```
Policy use: when the budget is exhausted, freeze risky releases and prioritize reliability work; when budget remains, ship features. This aligns product and operations.

Benefits over static thresholds: alerts scale with SLO strictness, detect both sharp and slow burns, reduce false pages and alert fatigue. Pair with symptoms-based alerts (users affected) and keep cause-based alerts (disk full, queue age) as tickets or low priority unless they predict an SLO breach.

Honesty: alert rule files in the repository are statically validated only unless run against a Prometheus (not executed here).

Measure: pages per week, false positive rate, time to detect.
**Follow-ups:**
- Why use two windows per alert?
- What happens to releases when the budget is gone?
- How do you alert on latency SLOs?
**Pitfalls:**
- Alerting on every error spike.
- No short window, so alerts stay on after recovery.
- No consequence policy for budget exhaustion.

### Q126. Design the SLO for "a transactional message is delivered to the customer". What are the pitfalls in measuring it?
**Tags:** SRE, Staff, Scenario
**Analogy:** Promising a parcel will arrive in two days: you control the dispatch speed, but not the courier's neighbourhood roads or whether the customer is home.
**Model answer:**
Clarify: what the business means by delivered and what timeframe matters per message class (security code in 30 seconds, trade confirmation within minutes, statement notice within hours).

Decompose the journey so each segment has its own SLI and owner:
1. Accept: availability and latency of the API (our control).
2. Dispatch delay: accepted to provider acceptance (`messaging.dispatch.delay`): our control. SLO: 99 percent under 10 s for `CRITICAL` and `HIGH`.
3. Provider acceptance rate: share of attempts ending `ACCEPTED` (shared control with providers).
4. Delivery confirmation: provider accepted to `DELIVERED` receipt within a window; depends on the receiving carriers and mailbox providers.

End-to-end SLO example: "99 percent of CRITICAL messages are provider-accepted within 15 seconds; 95 percent of provider-accepted SMS reach DELIVERED within 60 seconds, excluding invalid numbers and opted-out recipients."

Measurement pitfalls:
- Missing receipts: no `DELIVERED` event does not equal failure; `DELIVERY_UNKNOWN` messages must be a visible category, not silently counted as good or bad; reconciliation reduces them. Report "confirmed delivered", "confirmed failed", and "unknown" ratios.
- Denominator definition: exclude suppressed, opted-out and hard-bounced recipients as valid-event exclusions, or the SLO punishes the platform for correct behavior.
- Time-to-terminal needs `delivery_event.occurred_at` versus `received_at`; late callbacks skew windows.
- Email "delivered" means accepted by the recipient's server, not read; opens are unreliable.
- Provider differences: SLI per provider and channel, aggregated carefully (do not average percentages across different volumes).
- Clock skew across systems.
- Synthetic probes: send canary messages to test recipients per channel and provider to detect silent failures when real traffic is low.

Error budget policy: provider-attributable breaches trigger provider failover and vendor escalation, not our release freeze.

Measure: attainment per class and provider, unknown ratio, canary results.
**Follow-ups:**
- How do you treat messages with no receipt capability?
- What is your canary design?
- How do you attribute misses to us versus provider?
**Pitfalls:**
- Counting provider acceptance as delivery.
- Including invalid recipients in the denominator.
- Ignoring unknown states.

### Q127. Compare metrics, logs and traces. How do they work together, and what is the cardinality problem?
**Tags:** Observability, Intermediate, Concept
**Analogy:** Metrics are the dashboard gauges, logs are the diary of events, traces are the GPS track of one journey.
**Model answer:**
- Metrics: numeric time series, cheap to store and alert on (counters, gauges, histograms). Answer "is something wrong and how much". The platform uses Micrometer with a Prometheus registry (CONTRACT section 1) and named metrics such as `messaging.provider.calls{provider,channel,outcome}` (CONTRACT section 8).
- Logs: discrete events with context. Answer "what exactly happened to message X". Structured JSON with MDC fields `correlationId`, `tenant`, `messageId` (CONTRACT section 1), no PII (Q115). Centralized (ELK/OpenSearch/Loki).
- Traces: a tree of spans for a request across services, with timing. Answer "where did the time go and which component failed". Micrometer Tracing bridge to OpenTelemetry with an OTLP exporter (configurable endpoint, disabled locally). Across the queue: propagate trace context in message headers so the consumer span links to the producer span; the `DispatchEnvelope` carries `correlationId` (CONTRACT section 6).

Workflow: alert fires on a metric (SLO burn), dashboard narrows by channel/provider, exemplars or a correlation id jump to traces and logs for individual failures.

Cardinality: each unique combination of label values creates a separate time series. Labels like `messageId`, `recipientId`, user ids, or raw URLs explode series count, memory and cost, and can take down Prometheus (and the app's meter registry heap). Rules: use bounded, low-cardinality labels (channel, priority, provider, outcome, status); put high-cardinality identifiers in logs and traces; the contract's `messaging.bulk.progress{jobId}` gauge is deliberately a bounded exception (remove gauges when jobs end); normalize URI templates (`/messages/{id}`), not raw paths; histograms use fixed buckets; sample traces (head or tail sampling).

Measure: active series count, ingestion rate, log volume per service, trace sampling rate.
**Follow-ups:**
- What are exemplars?
- How do you choose trace sampling rates?
- What is tail-based sampling?
**Pitfalls:**
- Message ids as metric labels.
- Logging without correlation ids.
- Tracing that stops at the queue.

### Q128. Traces stop at the message queue, so you cannot follow a message from API to provider call. How do you fix distributed tracing across asynchronous boundaries?
**Tags:** Observability, Senior, Scenario
**Analogy:** A relay race where the baton (trace context) is dropped at each hand-off; you must hand it over inside the message.
**Model answer:**
Clarify: which tooling (Micrometer Tracing with OpenTelemetry, per CONTRACT section 1), whether context is stored with the message, and that the outbox adds another asynchronous hop (API request, outbox row, publisher, broker, consumer).

Why it breaks: trace context lives in thread-local/Context of the current request. The API handler ends; later the outbox publisher runs in a scheduler thread; the consumer runs in a worker thread. Without propagation each part starts a new trace.

Design:
- Persist trace context with the outbox row: store W3C `traceparent` (and `tracestate`) in the `outbox_event` (a small column or in the payload headers) at submission time.
- On publish, the publisher restores context and writes `traceparent` into message headers (AMQP headers); Spring AMQP with observation enabled does this when `observationEnabled` is on.
- Consumer extracts headers and starts a span as a child (or a linked span). For fan-out and retries use span links rather than a single parent: each attempt is a new span linked to the original trace; long delays (retry in 30 minutes) make a single trace awkward, so link them.
- Keep `correlationId` as a business-level identifier in MDC for logs, set at accept and carried in `DispatchEnvelope` and audit events (CONTRACT sections 1 and 6): it works even if tracing sampling drops traces.
- Callbacks: map provider callbacks to the message via ids; create a new trace and add attributes `messageId` and link to the original trace if stored.
- Executors: use context-propagating `TaskDecorator`/`ContextExecutorService` for async code.

Do not put PII in span attributes; use ids. Sampling: always sample errors and slow traces (tail sampling at the collector).

Verification: an integration test asserting a consumer span has the producer's trace id.

Measure: percentage of consumer spans with a parent, trace completeness.

<!-- in-project:b29 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/common/CorrelationIdFilter.java`
>
> The inbound id is accepted only if it matches a strict pattern (log-injection defence). The id travels in `DispatchEnvelope.correlationId`, and `DispatchConsumer.handle` puts it back into the MDC with `messageId` and `tenant`, so one grep follows a message across the queue boundary.
>
> ```java
> private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");
> // ...
> String incoming = request.getHeader(HEADER);   // X-Correlation-Id
> String id = incoming != null && SAFE.matcher(incoming).matches() ? incoming : Ids.newId();
> request.setAttribute(ATTRIBUTE, id);
> MDC.put(MDC_KEY, id);
> response.setHeader(HEADER, id);
> ```

**Follow-ups:**
- Why use span links for retries?
- Where do you store the trace context for scheduled messages?
- How do you keep trace cost low?
**Pitfalls:**
- One giant trace for a message that lives hours.
- Dropping the context at the outbox.
- Span attributes with personal data.

### Q129. The on-call engineer gets 200 alerts a day and ignores most of them. How do you fix alert fatigue?
**Tags:** SRE, Senior, Scenario
**Analogy:** A smoke alarm that goes off every time you make toast gets its battery removed, right before the real fire.
**Model answer:**
Clarify: inventory the alerts for 2 to 4 weeks: which fire most, which led to action, which were duplicates, and who is paged.

Principles: every page must be urgent, actionable, and require human intelligence; symptoms over causes; each alert linked to a runbook.

Steps:
1. Classify alerts: page (user-impacting now), ticket (needs action within days), dashboard-only (informational), and delete the rest. Track "actionability rate".
2. Replace cause-based thresholds (CPU above 80 percent, a single failed message) with SLO burn-rate alerts (Q125) and a few leading indicators (oldest message age beyond a threshold, DLQ growth for a sustained period, provider circuit open for more than 5 minutes).
3. Reduce noise mechanically: use `for:` durations, rate over windows, aggregation (alert per provider rather than per pod), inhibition (when the provider circuit is open do not also page every downstream symptom), grouping and deduplication in Alertmanager, maintenance silences with expiry.
4. Make alerts useful: include context (provider, channel, impact estimate, dashboard link, runbook, recent deploys).
5. Ownership and review: weekly review of top noisy alerts; each alert has an owner; delete or fix within a sprint; blameless discussions.
6. Capacity: rotation sizes and follow-the-sun to reduce burnout; measure pages per shift (a common target is at most a couple of actionable pages per 12-hour shift).
7. Automate remediation for known safe fixes (auto-restart stuck consumer) but alert on repeated remediation.

Measure: pages per week, percentage actionable, time to acknowledge, number of alerts without runbooks (target zero).
**Follow-ups:**
- What is an inhibition rule?
- How do you decide page versus ticket?
- How do you reduce noise in provider-caused alerts?
**Pitfalls:**
- Adding more alerts after an incident without removing any.
- Alerts with no runbook.
- Paging for non-urgent conditions.

### Q130. Dashboards look healthy (queue empty, error rate low) but customers say they are not receiving messages. How can this happen and how do you detect it?
**Tags:** Observability, Staff, Scenario
**Analogy:** The post office says all letters left on time, but the mail van drives into a lake: your tracking shows "dispatched", not "arrived".
**Model answer:**
Clarify: which channel, which recipients, which time window, and whether messages show as `SENT_TO_PROVIDER` or `DELIVERED`.

How "green dashboards, no delivery" happens (silent failures):
- Provider accepted but did not deliver (reputation, spam folder, carrier filtering, invalid sender id): our metrics count acceptance as success. Only delivery receipts and bounce rates reveal it.
- Webhook path broken: callbacks fail signature validation or never arrive, so messages remain `SENT_TO_PROVIDER` / `DELIVERY_UNKNOWN` and nobody alerts on that age.
- Suppression or policy rejects: `REJECTED(SUPPRESSED/OPTED_OUT)` is correct behavior, but a bad data load could suppress many recipients; rejections are not errors in HTTP metrics.
- Quiet-hours deferral misconfigured (time zone default), so messages wait in `VALIDATED` scheduled.
- Messages stuck before the queue: outbox not publishing (queue empty because nothing arrives), so queue depth zero looks fine while outbox lag grows.
- Wrong routing (disabled provider config, wrong template locale) leading to a default no-op.
- Consumer paused.
- Tracking only the broker, not the DB lifecycle.

Detection design:
- State-based gauges: counts and age by status (`messaging.transitions{from,to}`, ops summary), alert when oldest `VALIDATED`/`QUEUED`/`SENT_TO_PROVIDER`/`DELIVERY_UNKNOWN` exceeds thresholds.
- Outbox lag metric and alert; delivery ratio (DELIVERED / SENT_TO_PROVIDER) per provider and channel, with a drop alert; bounce and complaint rates; callback result counters (`messaging.callbacks{result}`), invalid signatures spike alert.
- Synthetic canary messages end to end per channel at intervals; alert if the canary does not reach `DELIVERED` within its SLO.
- Rejection reason dashboard with baseline anomaly detection.
- Reconciliation reports comparing our counts with provider reports.

Measure: delivery ratio, unknown count and age, canary success.
**Follow-ups:**
- Why is queue depth a poor health signal alone?
- How do you build a canary that does not annoy customers?
- Which view helps support find one customer's message?
**Pitfalls:**
- Equating acceptance with delivery in SLIs.
- Monitoring infrastructure but not business outcomes.
- No canary or end-to-end probe.

### Q131. Which dashboards and alerts would you build first for this platform?
**Tags:** Observability, Intermediate, Concept
**Analogy:** A car dashboard: speed, fuel, engine warning, and a few gauges; not every sensor reading in the engine bay.
**Model answer:**
Use the contract's metrics (CONTRACT section 8) and organize by audience and question.

Dashboard 1: Service health (golden signals) for API: request rate, error ratio (5xx), latency p50/p95/p99 by endpoint (`http_server_requests_seconds`), saturation (Hikari active/pending, thread pool, CPU, memory, GC), accepted messages by channel/priority/tenant (`messaging.messages.accepted`), rate-limit rejections (`messaging.ratelimit.rejected`).

Dashboard 2: Pipeline flow: transitions by `from,to` (`messaging.transitions`), queue depth by priority (`messaging.queue.depth`), in-flight, oldest message age, outbox lag, DLQ depth and rate by reason (`messaging.deadletter.total`), retries scheduled (`messaging.retry.scheduled`), dispatch delay histogram (`messaging.dispatch.delay`).

Dashboard 3: Providers: calls by outcome (`messaging.provider.calls`), latency p95 (`messaging.provider.latency`), circuit state (`messaging.provider.circuit.state`), rate limit utilization, delivery ratio, bounce and complaint rates.

Dashboard 4: Callbacks and reconciliation: `messaging.callbacks{result}` (duplicate, invalid_signature, out_of_order, stale), unknown count and age, reconciliation outcomes.

Dashboard 5: Bulk jobs: progress (`messaging.bulk.progress`), rows per second, skip rates, duplicates.

Dashboard 6: Dependencies: Redis fallback counter (`messaging.redis.fallback`), Mongo diagnostics failures (`messaging.diagnostics.write.failures`), DB latency, broker health.

Alerts (start small): accept availability and latency SLO burn, oldest message age above threshold, DLQ non-zero for more than 15 minutes, provider circuit open above 5 minutes, delivery ratio drop, invalid signature spike, outbox lag, DB pool pending sustained, JVM heap post-GC trend.

Each alert links to a runbook (`docs/operations/runbooks`, CONTRACT section 11). Honesty: dashboards and alert files are validated statically unless rendered (CONTRACT sections 11 and 12).

Measure: time to diagnosis in drills.
**Follow-ups:**
- What belongs on the first screen?
- How do you structure panels to match the pipeline?
- How do you test alert rules?
**Pitfalls:**
- Dozens of panels with no narrative.
- CPU graphs with no business metrics.
- No runbook links.

### Q132. A major SMS provider has a two-hour outage during business hours. How do you run the incident from detection to post-mortem?
**Tags:** SRE, Staff, Scenario
**Analogy:** A fire drill executed for real: someone commands, someone fights the fire, someone talks to the building residents, and afterwards everyone reviews what to improve.
**Model answer:**
Detection: alerts on provider circuit open, calls by outcome (TRANSIENT_FAILURE/TIMEOUT spike), dispatch delay, delivery ratio. Confirm via the provider status page and canary results.

Roles (incident command): incident commander (decisions and coordination), technical lead (diagnosis and mitigation), communications lead (status updates to stakeholders and customers), scribe (timeline). Declare severity based on impact (security-alert SMS affected is high).

Mitigation steps (CONTRACT sections 3 and 6):
- The circuit breaker opens and calls fail fast; messages move to `RETRY_PENDING` with backoff, not lost; check that the retry load is bounded (jitter, attempts, rate limiter).
- Failover: route SMS to the secondary SMS provider (`provider_config` priority order) where contracts allow; for time-critical categories (security alerts) consider fallback channels (push, email) honoring consent (fallback never bypasses consent).
- Reduce load: pause marketing sends (stop bulk jobs), keep critical priority flowing.
- Protect from ambiguity: timeouts lead to `DELIVERY_UNKNOWN`, avoid blind retries; track those for later reconciliation.
- Communicate: status updates every 30 minutes, stakeholder impact estimates (queued count by category, oldest age).

Recovery: close the breaker gradually (half-open), ramp rate limits, drain the backlog by priority, expire stale messages, reconcile unknowns, verify delivery ratios return to baseline.

Post-mortem (blameless, within a few days): timeline, impact (messages delayed, unknown, duplicates), what worked, what did not (alert delays, missing runbook, failover manual), action items with owners and due dates (automatic failover, canary improvements, contract SLAs with provider), and error budget accounting.

Measure: time to detect, time to mitigate, messages affected, customer-facing delay.
**Follow-ups:**
- How do you decide to fail over versus wait?
- How do you handle messages that expired during the outage?
- What belongs in the customer communication?
**Pitfalls:**
- No incident roles or single point of failure for decisions.
- Retrying everything instantly after recovery.
- Post-mortem without owned action items.

## AI-Assisted Development

### Q133. How would you use GitHub Copilot to accelerate delivery without compromising code quality?
**Tags:** AI-Assisted Development, Senior, Scenario
**Analogy:** A very fast junior colleague who has read a lot but does not know your codebase or its rules: great for drafts, never trusted without review.
**Model answer:**
Clarify: team maturity, codebase constraints (financial-services reliability, security), what "accelerate" means (cycle time, throughput), and which Copilot modes are available (inline completion, chat, agent mode, code review, prompt files).

Where it helps most: boilerplate (DTO records, mappers, controllers), unit-test scaffolding and edge-case ideas, refactoring suggestions, documentation drafts, explaining unfamiliar code, SQL and regex drafts, migration scripts drafts, translating specs to code skeletons.

Where to be careful: concurrency, idempotency, transactions, security-sensitive code, performance-critical paths, anything touching money or consent. These need human design first.

Quality guardrails:
- Spec first: write acceptance criteria and constraints (CONTRACT-like documents, `specs/`) before prompting; give Copilot the spec, not vague asks.
- Repository instructions: `.github/copilot-instructions.md` and path-specific `.github/instructions/*.instructions.md` encode conventions (Java 21, constructor injection, no field injection, error model, no PII logging, test naming), so suggestions follow standards.
- Reusable prompt files for recurring tasks (add endpoint, add provider, write migration).
- Small, reviewable changes: ask for one unit at a time; keep diffs small; commit often.
- Tests as the contract: write or review tests first; require the generated tests to fail for the right reasons (mutation testing or deliberately broken implementation) so tests do not merely mirror the code.
- Same gates for all code: CI (build, tests, static analysis, SCA, secret scan), human code review, no exceptions for AI-authored code. Developer remains accountable.
- Security and privacy: no secrets or customer data in prompts; use enterprise tenancy settings with data exclusions; verify dependencies suggested exist and are approved.
- Review checklist for AI code (Q135).

Measure: lead time, PR size, defect escape rate, review rework rate, test quality (mutation score), developer survey; avoid vanity metrics (lines generated, suggestion acceptance rate).
**Follow-ups:**
- What would you not delegate to Copilot?
- How do you onboard junior developers to use it responsibly?
- How do you measure that it helps?
**Pitfalls:**
- Accepting large suggestions unread.
- Generated tests that duplicate implementation logic.
- Pasting production data or secrets in prompts.

### Q134. What is spec-driven development, and how would it apply to this platform when working with AI assistants?
**Tags:** AI-Assisted Development, Senior, Concept
**Analogy:** An architect's blueprint approved before bricklaying starts: the builders (humans or AI) follow the blueprint, and inspectors check the building against it.
**Model answer:**
Spec-driven development makes a written, reviewable specification the primary artifact; code and tests are derived from and checked against it. With AI assistants it gives the model precise, bounded context and gives reviewers an objective yardstick.

Workflow:
1. Requirements and constraints: user stories, acceptance criteria in Given/When/Then, non-functional requirements (latency, idempotency, security), and explicit non-goals.
2. Design/spec artifacts: API contract (OpenAPI), data model and migrations, state machine table, error codes, sequence diagrams, ADRs capturing decisions (the platform's CONTRACT, ADRs, committed OpenAPI spec, and `specs/` folder follow this pattern).
3. Task breakdown: small, independently verifiable tasks, each referencing spec sections.
4. Implementation by humans or agents against a single task; the agent receives the spec excerpt, interfaces, and constraints.
5. Verification: tests derived from acceptance criteria; contract tests (springdoc output equals the committed spec, CONTRACT section 1); traceability from requirement to test (a traceability matrix).
6. Review: a human compares the diff to the spec; deviations are recorded (the contract requires appending to a deviations log instead of silently changing shared decisions).

Benefits: reduces hallucinated behavior, enables parallel work with multiple agents (clear interfaces), improves reviews and onboarding, preserves intent. Costs: spec drift if not maintained, overhead for small changes, risk of over-specifying; mitigate with living docs checked by tests.

For this platform, the spec for the state machine and policy order makes AI-generated transition code verifiable by table-driven tests.

Measure: spec coverage by tests, spec-code drift incidents, rework rate.
**Follow-ups:**
- How do you keep the spec in sync with code?
- What belongs in an ADR?
- How do you split a spec for multiple agents?
**Pitfalls:**
- Specs nobody reads or updates.
- Prompting from memory instead of the spec.
- Treating the spec as unchangeable when evidence says otherwise.

### Q135. How would you review AI-generated code for race conditions and missing idempotency?
**Tags:** AI-Assisted Development, Staff, Scenario
**Analogy:** Proofreading a confident writer's essay for the mistakes that only appear when two people edit at the same time.
**Model answer:**
Clarify: what the code does (consumer, callback handler, scheduler, API write) and what duplicate or concurrent execution would mean for it. AI-generated code is often correct for a single thread and a single delivery, which is exactly where production differs: multiple instances, redelivery, retries, and crashes.

Review checklist:
1. Identify every entry point that can run twice or concurrently: queue consumers (at-least-once redelivery), HTTP retries, scheduled pollers on several instances, callbacks (provider duplicates), restarts of batch jobs.
2. For each, ask "what if this runs twice, in parallel, or stops halfway?" and look for protections:
 - Dedupe with a unique constraint (idempotency key, `processed_message.dispatch_id`, `UNIQUE(provider_id, provider_event_id)`), not check-then-insert.
 - State changes by compare-and-set (`UPDATE ... WHERE status=:expected`) and a state machine table, not read-modify-write.
 - Transaction boundaries: no remote calls inside transactions, outbox for DB-plus-broker consistency, `REQUIRES_NEW` pitfalls (CONTRACT section 6).
 - Shared mutable state: `HashMap` in singletons, non-thread-safe `SimpleDateFormat`, lazy init without safe publication; static caches without bounds.
 - Ordering assumptions: callbacks out of order, rank rules (CONTRACT section 3.4).
 - Timeouts as ambiguity: retry on timeout without idempotency ref is a duplicate send.
 - Retry logic: bounded, jittered, only for transient errors; no retry stacking.
3. Look for AI-typical errors: hallucinated APIs or wrong library versions, swallowed exceptions, optimistic "happy path" tests, Optional misuse, over-broad `catch`, logging PII, missing `@Transactional` boundary or self-invocation.
4. Demand evidence: a concurrent test (N threads, latch, assert exactly one winner), a duplicate-delivery test (same event twice), and a failure-injection test (crash between provider call and persist) (CONTRACT section 11 `tests/failure-recovery`).
5. Run static analysis and mutation testing on the critical logic.
6. Ask the author to explain the invariants; if they cannot, the change is not ready.

Measure: defects found in review by category, concurrency test coverage on critical packages.
**Follow-ups:**
- Which three tests would you demand for a new callback handler?
- How do you spot check-then-act in a diff?
- When do you reject AI-generated code entirely?
**Pitfalls:**
- Reviewing only style and compile success.
- Trusting passing unit tests that do not simulate concurrency.
- Accepting retries without idempotency.

### Q136. What is context engineering, and how do you structure repository context for an AI coding assistant?
**Tags:** AI-Assisted Development, Senior, Concept
**Analogy:** Giving a new contractor the right three binders on day one instead of the entire company archive.
**Model answer:**
Context engineering is designing what information the model sees: relevant, accurate, minimal, and well organized, so output quality rises and cost and noise fall. The model only knows its training data plus the context window; anything about your architecture, conventions and constraints must be provided.

Layers of context:
- Persistent repository instructions: `.github/copilot-instructions.md` (always included) for build commands, language and framework versions, coding standards, security rules (no secrets, no PII in logs), testing expectations, and architecture overview; `AGENTS.md` for agent-style tools. Keep them short and specific, not a copy of the wiki.
- Path-scoped instructions: `.github/instructions/*.instructions.md` with `applyTo` globs (for example Java tests, SQL migrations, Angular components), so rules load only when relevant.
- Reusable prompts (`.github/prompts/`) for repeated workflows with explicit steps and acceptance checks.
- Task context: the specific spec excerpt, interfaces, a failing test, error logs, and relevant files; not whole directories.
- Retrieval: workspace indexing, symbol search, `#file` references; prefer pointing to authoritative documents (ADRs, OpenAPI) to avoid duplicates that drift.
- Memory and state for long tasks: a progress file or plan the agent updates, so a fresh context can resume.
- Negative context: what not to touch (generated code, vendor folders) and explicit non-goals.

Principles: accuracy over volume (stale docs mislead), deterministic facts (version numbers, command lines), examples of preferred patterns, and verification hooks (commands to run tests and linters so the agent can self-check). Treat context files as code: reviewed, versioned and tested by observing outputs.

Risks: sensitive data in context, prompt injection through untrusted content (Q140), context overflow causing the model to ignore early instructions.

Measure: rework rate of AI-generated PRs, first-pass test success, instruction adherence in reviews.

<!-- in-project:b38 -->
> [!NOTE] In this project
> **File:** `AGENTS.md`
>
> Repository-level context for coding agents, kept short on purpose; longer guidance sits in `ai-engineering/` (context-engineering.md, spec-driven-workflow.md, multi-agent-workflow.md, skills/, ai-code-review/).
>
> ```markdown
> ## Non-negotiable rules
> 1. Message status changes only via MessageStateMachine with compare-and-set; no direct status setters.
> 2. Accepted means durable: persist message + outbox + idempotency + audit in one transaction; publish after commit.
> 3. Idempotency at API, consumer, callback and provider call.
> 4. Never hold a DB transaction across a provider call; every remote call has a timeout; pools/queues are bounded.
> 5. Provider acceptance is not delivery; timeouts/ambiguous outcomes become DELIVERY_UNKNOWN.
> 6. Tenant comes only from the verified JWT; every tenant-owned query is tenant-scoped; foreign resources return 404.
> ```

**Follow-ups:**
- Why not put the whole architecture document in every prompt?
- How do path-specific instructions help?
- How do you keep context files from going stale?
**Pitfalls:**
- A giant instructions file nobody maintains.
- Contradictory instructions.
- Putting secrets or customer data into context.

### Q137. How do you optimize token usage and cost when working with AI coding assistants at team scale?
**Tags:** AI-Assisted Development, Senior, Concept
**Analogy:** Paying for a taxi by the kilometre: choose the shortest route, carry only what you need, and do not make the driver circle the block.
**Model answer:**
Tokens drive latency, cost, and quality: more irrelevant context can degrade results ("lost in the middle") and slow responses.

Tactics:
- Scope the task: one function or one endpoint per request; split large changes into steps, each with fresh, minimal context.
- Select context deliberately: reference specific files, symbols, and spec sections instead of whole folders; exclude generated code, lockfiles, build output, large logs (trim stack traces to the relevant frames).
- Summarize and compact: for long sessions, maintain a short plan/state file and restart with a clean context instead of dragging a 100k-token transcript; ask the model to summarize decisions.
- Concise instructions: keep always-on instruction files small; move specialized guidance to path-scoped or on-demand prompt files.
- Structured outputs and diffs: request patches or only changed files, not full rewrites; ask for brief explanations unless needed.
- Model selection: use smaller, faster models for boilerplate, refactoring and search; reserve the strongest model for design, debugging hard concurrency issues and reviews. Use caching features where the tool supports prompt caching by keeping stable prefixes (instructions, spec) first and variable content last.
- Avoid loops: set step limits on agents, clear stop criteria, and human checkpoints to prevent expensive wandering.
- Use deterministic tools: let linters, tests, `grep` and the compiler do verification instead of asking the model to "check".
- Governance: budgets per team, usage dashboards, review of top consumers, training on prompt hygiene.

Trade-off: over-trimming context removes necessary facts and increases errors and rework, which costs more than tokens. Optimize cost per accepted change, not cost per request.

Measure: tokens per merged PR, rework iterations, cost per feature, time to first correct result.
**Follow-ups:**
- When is a bigger context window the wrong fix?
- How does prompt caching work conceptually?
- How do you choose the model per task?
**Pitfalls:**
- Pasting the whole repository.
- Long single sessions with stale context.
- Measuring only cost per request.

### Q138. How would you design a multi-agent workflow (planner, implementer, tester, reviewer) with human approval gates for this platform?
**Tags:** AI-Assisted Development, Staff, Concept
**Analogy:** A small crew where each member has one job and a foreman signs off before anything ships.
**Model answer:**
Goal: parallelize and standardize work while keeping accountability human. Roles with narrow responsibilities and least privilege tools:
- Planner/architect agent: reads specs and ADRs, produces a task breakdown with interfaces and acceptance criteria; read-only access; output reviewed by a human before implementation.
- Implementer agents: each owns a bounded task and a set of files/packages (for example `callback/` or `batch/`), works in a branch or worktree, follows repository instructions; may write code and run build/tests; cannot merge or change shared contracts (CONTRACT-like files) except by proposing a deviation entry.
- Test agent: writes tests from acceptance criteria independently of the implementer (reduces mirror-testing), including concurrency and failure tests.
- Reviewer/security agent: reviews diffs against spec, checklist (idempotency, races, authz, PII), runs static analysis, and reports findings; advisory only.
- Documentation agent: updates docs from verified behavior only; must cite command outputs and mark non-executed items as not executed.
- Release/ops agent (optional): proposes release notes and runbook updates; no production credentials.

Human gates: approve the plan; approve any change to shared contracts, security configuration, database migrations, and dependency additions; review every PR; approve deployments. Agents never get production secrets, never push to protected branches, and never self-approve.

Coordination: shared spec as source of truth, explicit interfaces to avoid integration drift, ownership map to avoid conflicting edits, an integration step that builds and runs the full test suite, and a deviations log.

Risks: compounding errors across agents, confident but wrong claims (hallucinated test results: require actual command output), prompt injection via files or issues, cost loops. Mitigate with step limits, evidence requirements, audit logs of agent actions, sandboxing, and least privilege.

Measure: integration defects, rework rate, percent of agent claims verified by commands, review time.
**Follow-ups:**
- How do you prevent agents from overwriting each other's work?
- What evidence must an agent provide for "tests pass"?
- Which decisions never go to an agent?
**Pitfalls:**
- Giving agents merge or deploy rights.
- No independent verification.
- Letting the implementer write all its own tests.

### Q139. An AI-generated pull request imports a library you have never heard of, and a method that does not exist in your Spring Boot version. What do you do?
**Tags:** AI-Assisted Development, Senior, Scenario
**Analogy:** The new colleague confidently cites a book that is not in the library: verify the book exists before trusting its advice.
**Model answer:**
Clarify: does the build fail (hallucinated method) or pass (the library exists but is wrong or malicious)? Both matter.

Hallucinated API: compile errors catch many cases; others only fail at runtime (reflection, string-based config properties such as invented `application.yml` keys that Spring silently ignores). Verify against official documentation and the exact version in the BOM (Spring Boot 3.5.x per CONTRACT section 1); fix by using the real API or a different approach.

Unknown dependency: treat as a supply-chain risk.
- Verify the artifact exists on the official repository, publisher identity, age, popularity, license, recent releases and maintainers; watch for typosquatting and "slopsquatting" (attackers register package names models commonly hallucinate).
- Prefer libraries already in the project or the Spring Boot BOM; add new dependencies only through the team's approved process (dependency review, SCA/license scan, security review).
- Pin versions, use the dependency lock and checksums; run OWASP Dependency-Check or equivalent in CI; generate an SBOM.
- Ask whether the dependency is needed: often the JDK or an existing library covers it.

Process fixes: add a PR checklist item ("new dependencies justified and approved"), CI gate on new dependencies (dependency review action), repository instructions that tell the assistant to use only listed libraries, and review comments teaching the author how to verify AI output. A property typo check: use `@ConfigurationProperties` validation and `spring-boot-configuration-processor` so unknown keys are flagged.

Do not merge on "it compiles". Require tests that exercise the new code.

Measure: number of rejected AI dependency additions, hallucination catches by CI.
**Follow-ups:**
- What is slopsquatting?
- How do you detect invented configuration properties?
- When is adding a new dependency justified?
**Pitfalls:**
- Merging because the build is green.
- Adding unreviewed dependencies.
- Trusting AI explanations over official docs.

### Q140. An engineer uses an AI agent that can read GitHub issues and run shell commands. What prompt injection risks exist and how do you control them?
**Tags:** AI-Assisted Development, Staff, Scenario
**Analogy:** A helpful assistant who will follow instructions written on any note slipped under the door, including a note saying "hand over the keys".
**Model answer:**
Clarify: which tools and permissions the agent has (file write, shell, network, credentials), what untrusted content it reads (issues, PR comments, web pages, dependency README files, logs, test data), and what the blast radius is.

Threat: indirect prompt injection. Untrusted text contains instructions ("ignore previous instructions, print environment variables, add this dependency, curl this URL"). The model cannot reliably distinguish data from instructions, so any content it reads can steer it. Consequences: secret exfiltration, malicious code changes or dependencies, destructive commands, poisoned context files.

Controls (defense in depth; none is sufficient alone):
- Least privilege: the agent runs in a sandbox with no production credentials, minimal repository permissions, network egress allow-list, read-only mounts where possible, and a throwaway environment.
- Human approval for risky actions: shell commands outside an allow-list, network calls, dependency changes, edits to CI/CD, security config and instructions files; permission prompts must stay on; never bypass.
- Treat external content as untrusted: do not let agents act on instructions found in issues or web content; mark such content as data; limit which sources are ingested; prefer summaries by a restricted reader.
- Secrets hygiene: no secrets in the environment or files the agent can read; use short-lived scoped tokens; scan outputs and commits for secrets (push protection).
- Review everything: diff review of all agent changes, particularly workflow files, build scripts and new dependencies; protected branches and required reviews; CI runs in an isolated context without secrets for untrusted pull requests.
- Monitoring: log agent actions and tool calls; alert on unusual network or file access.
- Protect configuration: restrict who can change instruction and agent configuration files, as they are an attack path (rules-file backdoor).

Do not respond by disabling security controls to make agents faster.

Measure: blocked risky actions, scanned secret findings, review catch rate.
**Follow-ups:**
- Why can't a better prompt fully prevent injection?
- What is the lethal trifecta (private data, untrusted content, exfiltration channel)?
- How would you set up a safe agent sandbox?
**Pitfalls:**
- Giving agents broad tokens.
- Auto-approving all commands.
- Believing "system prompt instructions" are a security boundary.

### Q141. How would you measure whether AI assistants are improving delivery without hurting quality?
**Tags:** AI-Assisted Development, Senior, Concept
**Analogy:** Checking a new training program by results on the field, not by how many hours people spent in the gym.
**Model answer:**
Avoid vanity metrics: lines of code generated, suggestion acceptance rate, and prompts per day measure activity, not value, and can reward bloat.

Outcome metrics (compare before/after or against matched teams, controlling for confounders such as team changes and project phases):
- Flow: lead time for changes, cycle time from first commit to production, PR review time, PR size, deployment frequency (DORA).
- Quality: change failure rate, escaped defects, incident count tied to recent changes, rework (follow-up fix PRs), reverted changes, test coverage and mutation score on critical packages, flaky test rate.
- Security: vulnerabilities introduced (SAST/SCA findings per KLOC), secrets detected, new dependency approvals.
- Maintainability: code churn within two weeks of merge, duplication, review comment categories.
- Developer experience: surveys (time saved, cognitive load, trust), onboarding time for newcomers, perceived quality.
- Cost: license and token spend per merged change.

Method: baseline for at least a couple of sprints; pilots with a control group; tag AI-assisted PRs voluntarily (a label) to compare quality and review effort without surveillance; qualitative reviews of sampled PRs; track learning effects (juniors maintaining understanding). Watch for risks: faster code production moving the bottleneck to review and testing, review fatigue, and skill atrophy.

Honesty: report uncertainty; the platform's AI documentation should not claim measured productivity unless actually measured.

Decisions: use data to adjust guidelines (where AI helps, where it harms), training, and instruction files.

Measure: the above, reviewed monthly.
**Follow-ups:**
- Why is acceptance rate misleading?
- How do you avoid developer surveillance?
- What does a good pilot look like?
**Pitfalls:**
- Counting lines of code.
- No baseline.
- Ignoring review and test bottlenecks.

### Q142. A junior developer relies heavily on Copilot. Their tests are green but simply restate the implementation, and they cannot explain a transaction boundary in their own PR. How do you coach them?
**Tags:** AI-Assisted Development, Senior, Scenario
**Analogy:** A student who copies answers and gets full marks on homework but cannot solve a problem on the exam board.
**Model answer:**
Clarify (privately, supportively): what they do understand, how they use the tool, and what pressure they feel (deadlines, fear of looking slow). The goal is to build judgment, not to ban the tool.

Coaching plan:
- Make the expectation explicit: authors own every line; "Copilot wrote it" is not an explanation. Use PR descriptions that state intent, invariants and risks in their own words.
- Pair on a real change: have them write the test first (from the acceptance criteria) before prompting, then use the assistant for the implementation draft; compare.
- Teach how to evaluate tests: break the implementation on purpose (mutation testing, flip a condition) and check that tests fail; add edge cases (duplicates, concurrency, boundaries); distinguish asserting behavior from asserting implementation (mocks verifying call order).
- Concept drills on their own PRs: ask them to draw the transaction boundary, list what happens on retry or crash, and explain why a design choice was made; short, regular, low-pressure sessions; recommended reading (transactions, idempotency, the platform's deep-dive chapters).
- Gradual autonomy: smaller tasks with the assistant restricted to specific uses (boilerplate, test case ideas), then expanding; code walk-throughs; a rotating "explain this module" presentation.
- Review feedback style: ask questions ("what happens if this message is delivered twice?") rather than only correcting, so they learn the habit.
- Process support: checklists, repository instructions that encode standards, quality gates that catch weak tests.
- Measure progress: quality of PR descriptions, number of review iterations, ability to explain, mutation score on their tests, independent problem-solving tasks.

Escalate only if the pattern persists after support; treat it as a learning gap, not misconduct, unless confidential data was pasted into prompts (a policy matter).

Measure: review rework, escaped defects, mentee confidence and independence.
**Follow-ups:**
- How do you give this feedback without discouraging them?
- Would you restrict the tool?
- How do you involve the wider team?
**Pitfalls:**
- Banning the tool outright.
- Public criticism.
- Only fixing the code in review without teaching.
