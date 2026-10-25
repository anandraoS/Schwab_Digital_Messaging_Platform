# 20. Observability and Performance

> [!WARNING] Validation status
> Prometheus, alert, recording-rule, Grafana and OpenTelemetry files were validated by **parsing only** (`bash scripts/validate-infra.sh`). `promtool` is not installed, so the PromQL was not checked by Prometheus, and no dashboard was rendered by Grafana. The k6 scripts were **not executed**. Throughput and latency numbers are quoted only from `docs/validation/` (see `docs/validation/VALIDATION_REPORT.md` and `docs/validation/performance-results.md` if present); this chapter's capacity charts are **illustrative arithmetic**.

## 20.1 Three signals

{{diagram:observability}}

| Signal | Source | Destination | Notes |
|---|---|---|---|
| Metrics | Micrometer, `/actuator/prometheus` | Prometheus (`observability/prometheus/prometheus.yml`) | Percentile histograms enabled for `http.server.requests`, `messaging.dispatch.delay`, `messaging.provider.latency` |
| Traces | `micrometer-tracing-bridge-otel` plus OTLP exporter | OTel Collector (`observability/otel/otel-collector.yaml`), `debug` exporter by default | Disabled locally; 10 percent sampling to `otel-collector:4318` in Compose |
| Logs | JSON console logs | stdout, shipped by the platform agent | MDC keys `correlationId`, `tenant`, `messageId`; no content or addresses |

Correlation is end to end: `CorrelationIdFilter` accepts or generates `X-Correlation-Id`, echoes it in the response and in every `ApiError`, copies it into the MDC, stores it on the message (`correlation_id`) and carries it in the `DispatchEnvelope`, so a customer complaint can be followed from the API call through the queue to the provider attempt.

## 20.2 Metrics catalogue

The meter names are an API: dashboards and alerts depend on them (`metrics/MessagingMetrics`). Micrometer's Prometheus naming turns dots into underscores, adds `_total` to counters and `_seconds` to timers.

| Meter | Type | Tags | Prometheus series |
|---|---|---|---|
| `messaging.messages.accepted` | counter | `channel`, `priority`, `tenant` | `messaging_messages_accepted_total` |
| `messaging.transitions` | counter | `from`, `to` | `messaging_transitions_total` |
| `messaging.dispatch.delay` | timer | | `messaging_dispatch_delay_seconds_*` |
| `messaging.provider.calls` | counter | `provider`, `channel`, `outcome` | `messaging_provider_calls_total` |
| `messaging.provider.latency` | timer | `provider` | `messaging_provider_latency_seconds_*` |
| `messaging.provider.circuit.state` | gauge | `provider` (0 closed, 1 half-open, 2 open) | `messaging_provider_circuit_state` |
| `messaging.queue.depth` | gauge | `priority` | `messaging_queue_depth` |
| `messaging.queue.inflight`, `messaging.queue.dlq.depth` | gauge | | `messaging_queue_inflight`, `messaging_queue_dlq_depth` |
| `messaging.queue.oldest.age` | gauge | seconds (added in stage 2) | `messaging_queue_oldest_age` |
| `messaging.retry.scheduled` | counter | `channel` | `messaging_retry_scheduled_total` |
| `messaging.deadletter.total` | counter | `reason` | `messaging_deadletter_total` |
| `messaging.callbacks` | counter | `result` (accepted, duplicate, replayed, invalid_signature, out_of_order, stale, conflict, unknown_message, malformed) | `messaging_callbacks_total` |
| `messaging.bulk.progress` | gauge | `jobId` | `messaging_bulk_progress` |
| `messaging.idempotency.hits` | counter | | `messaging_idempotency_hits_total` |
| `messaging.ratelimit.rejected` | counter | `scope` | `messaging_ratelimit_rejected_total` |
| `messaging.redis.fallback` | counter | | `messaging_redis_fallback_total` |
| `messaging.diagnostics.write.failures` | counter | | `messaging_diagnostics_write_failures_total` |
| `messaging.reconciliation` | counter | `result` | `messaging_reconciliation_total` |
| `messaging.bulk.writers.active`, `messaging.retention.deleted`, `messaging.retention.run` | gauge, counter | `table`, `dryRun` | additive |

Plus Spring, HikariCP and JVM defaults (`hikaricp_connections_*`, `http_server_requests_seconds`). Two implementation notes from the deviations record: Micrometer holds gauge suppliers weakly, so `MessagingMetrics` keeps strong references to the suppliers handed to `bindQueueStats` and `bindCircuitState` (otherwise gauges turned `NaN` after a garbage collection); and `publishPercentiles(0.5, 0.95, 0.99)` on the dispatch and provider timers supplies the p95 values shown by `/providers` and `/ops/summary`.

## 20.3 SLIs, SLOs and alerts

All targets are **illustrative**, not commitments of any organisation, and not derived from measured production traffic. The table summarises `docs/operations/slis-slos.md`.

| SLI | Illustrative SLO (30 days) |
|---|---|
| API availability on `POST /api/v1/messages` | 99.9 percent |
| API latency | 99 percent under 300 ms, p95 under 150 ms |
| Acceptance durability (no accepted message lost) | 99.99 percent |
| Dispatch delay | p95 under 5 s CRITICAL, 30 s HIGH, 5 min NORMAL |
| Provider submission success | 99 percent per provider |
| Callback processing | 99.9 percent under 10 s |
| Backlog health | oldest queued CRITICAL under 30 s, NORMAL under 15 min |
| Unresolved ambiguity | `DELIVERY_UNKNOWN` older than 30 min under 0.1 percent of volume |
| DLQ | depth under 100, no growth for more than 1 h |

{{diagram:sloBurn}}

`observability/prometheus/alerts.yml` defines **19 alerts**, each carrying a `runbook_url`, with multi-window burn-rate alerting for the 99.9 percent availability objective (14.4x over 1 h and 5 min pages; 6x over 6 h and 30 min raises a ticket). Examples: `ProviderErrorRatioHigh`, `ProviderCircuitOpen`, `QueueDepthHigh`, `DispatchDelayHigh`, `DlqGrowth`, `RetryStorm`, `DeliveryUnknownGrowth`, `CallbackInvalidSignatureSpike`, `HikariPoolSaturation`, `RedisFallbackActive`, `BulkJobStalled`. `scripts/lib/static-checks.mjs` fails if a `messaging_*` series in the observability files is not derived from the meter catalogue.

Five Grafana dashboards are generated by `observability/grafana/build-dashboards.mjs` and committed: API and SLO, Dispatch and Queues, Providers, Bulk and Batch, JVM and DB Pool.

## 20.4 Performance engineering approach

Performance work follows a fixed method (`docs/operations/performance-investigation-guide.md`): measure first, find the saturated resource (CPU, DB pool, queue, provider limit), change one thing, measure again. The structural choices that keep latency and throughput predictable are in the design, not in tuning:

| Design choice | Performance effect |
|---|---|
| 202 after one short transaction, no provider call on the request thread | Submit latency independent of provider latency |
| No transaction across a provider call | Connection pool bounded by DB time |
| Separate provider executor | A hanging provider cannot starve workers or Tomcat |
| Priority queues, prefetch 16, 8 workers | Urgent traffic first; bounded in-flight work |
| Token bucket per provider | The platform never exceeds a vendor quota |
| Streaming CSV reader and chunked commits | Bulk memory is constant, not proportional to file size |
| Indexes matched to hot queries | Poller, outbox and list queries avoid scans |
| No N+1 | Fetch joins, projections; `RepositoryQueryCountIT` |
| Backpressure | Overload becomes a clean 503 rather than a collapse |

### Illustrative capacity arithmetic

The high-level design sketches capacity as arithmetic, to show **how to reason**, not what the system achieves. Assume a 300 ms provider call and 8 workers per instance:

{{diagram:capacityThroughput}}

{{diagram:capacityInstances}}

The second chart assumes a target of 500 messages per second sustained and shows why provider latency, not platform code, dominates the instance count, and why a vendor rate limit usually binds before the platform does. None of these numbers was measured.

## 20.5 Load tests and what was and was not run

| Tool | Status | What it is for |
|---|---|---|
| `performance/node-load/load-driver.mjs` | Runnable locally; a result exists only if recorded in `docs/validation/` | Zero-dependency driver with p50, p95, p99 for submit, read, mixed and idempotent scenarios |
| In-JVM tests (`BulkThroughputIT`) | Run locally on H2 and the in-memory bus as part of the Maven build; any measured number is quoted only from `docs/validation/` | Throughput of the bulk pipeline in one JVM |
| `performance/k6/*.js` (8 scripts) | **NOT executed** | submit-high-volume, concurrent-consumers, provider-rate-limit, bulk-processing, db-throughput, backlog-recovery, retry-storm, dashboard-queries |

> [!IMPORTANT] How to read any local number
> Local runs use H2 and the in-memory bus; they say nothing about PostgreSQL or RabbitMQ capacity. Simulated providers have artificial latency and fault models, so the "provider rate limit" scenario exercises the platform's limiter and requeue logic, not a real vendor.

## Reference material

{{embed:docs/operations/observability.md|title=Observability}}

{{embed:docs/operations/slis-slos.md|title=SLIs, SLOs and alerting}}

{{embed:docs/operations/performance-tests.md|title=Performance test plan}}

{{embed:docs/operations/performance-investigation-guide.md|title=Performance investigation guide}}

{{embed:docs/operations/runbooks/high-latency-and-resource-saturation.md|title=Runbook: High latency and resource saturation}}

{{embed:docs/operations/runbooks/queue-backlog.md|title=Runbook: Queue backlog}}
