# Observability

**Validation status.** Prometheus, alert, recording-rule, Grafana and OTel files were validated by parsing only
(`bash scripts/validate-infra.sh`: YAML/JSON syntax plus structural checks). `promtool` is not installed here, so the PromQL
was **not** checked by Prometheus, and no dashboard has been rendered by Grafana. Nothing was scraped from a running
backend by these files. Treat all thresholds as illustrative starting points.

## Signals
| Signal | Source | Destination |
|---|---|---|
| Metrics | Micrometer, `/actuator/prometheus` | Prometheus (`observability/prometheus/prometheus.yml`) |
| Traces | `micrometer-tracing-bridge-otel` + OTLP exporter | OTel Collector (`observability/otel/otel-collector.yaml`), `debug` exporter by default |
| Logs | JSON console logs with MDC `correlationId`, `tenant`, `messageId` | stdout (ship with your platform agent) |

Locally, tracing export is disabled; in compose the backend sends OTLP/HTTP to `otel-collector:4318` at 10% sampling.

## Metric naming
CONTRACT section 8 meter names become Prometheus names by Micrometer rules: dots become underscores, counters gain
`_total`, timers gain `_seconds` (with `_count`, `_sum`, `_bucket`).

| CONTRACT meter | Prometheus series |
|---|---|
| `messaging.messages.accepted{channel,priority,tenant}` | `messaging_messages_accepted_total` |
| `messaging.transitions{from,to}` | `messaging_transitions_total` |
| `messaging.dispatch.delay` | `messaging_dispatch_delay_seconds_{count,sum,bucket}` |
| `messaging.provider.calls{provider,channel,outcome}` | `messaging_provider_calls_total` |
| `messaging.provider.latency{provider}` | `messaging_provider_latency_seconds_*` |
| `messaging.provider.circuit.state{provider}` | `messaging_provider_circuit_state` (0 closed, 1 half-open, 2 open) |
| `messaging.queue.depth{priority}` / `.inflight` / `.dlq.depth` | `messaging_queue_depth`, `messaging_queue_inflight`, `messaging_queue_dlq_depth` |
| `messaging.retry.scheduled{channel}` | `messaging_retry_scheduled_total` |
| `messaging.deadletter.total{reason}` | `messaging_deadletter_total` |
| `messaging.callbacks{result}` | `messaging_callbacks_total` |
| `messaging.bulk.progress{jobId}` | `messaging_bulk_progress` |
| `messaging.idempotency.hits`, `messaging.ratelimit.rejected{scope}`, `messaging.redis.fallback`, `messaging.diagnostics.write.failures` | `*_total` counters |

`scripts/lib/static-checks.mjs` fails if any `messaging_*` name in observability files is not derived from section 8.

### Assumptions to verify against the running backend
1. Histogram buckets (`*_bucket`) exist only if percentile histograms are enabled for `http.server.requests`,
   `messaging.dispatch.delay` and `messaging.provider.latency` (`management.metrics.distribution.percentiles-histogram.*`).
   Without them the p95 recording rules and three alerts (`DispatchDelayHigh`, `ApiLatencyP95High`, provider latency panel) return no data.
2. `messaging.bulk.progress` is treated as a 0..1 fraction for `BulkJobStalled`. If it is a row count, change the `< 1` bound.
3. CONTRACT has no "oldest message age" meter even though the ops API reports one. Dispatch-delay p95 and queue-depth slope
   are used as proxies. Suggested addition (not applied, record in DEVIATIONS.md if adopted): gauge `messaging.queue.oldest.age`.

## Recording rules (`recording-rules.yml`)
SLIs: API request/error rate and error ratio over 5m/30m/1h/6h, API p95, dispatch delay p95, accept rate, total queue depth,
provider call rate, failure ratio and p95 latency per provider, invalid-signature rate.

## Alerts (`alerts.yml`, 19 alerts)
| Alert | Meaning | Severity | Runbook |
|---|---|---|---|
| ProviderErrorRatioHigh | >20% provider calls failing 5m | page | provider-outage |
| ProviderCircuitOpen | breaker state 2 for 2m | page | provider-outage |
| ProviderRateLimited | sustained RATE_LIMITED outcomes | ticket | provider-rate-limit |
| QueueDepthHigh | >10k queued for 10m | page | queue-backlog |
| QueueBacklogGrowing | depth slope >5/s for 15m | ticket | queue-backlog |
| DispatchDelayHigh | p95 accepted-to-provider >30s | page | queue-backlog |
| DlqGrowth | DLQ growth or depth >100 | page | dlq-growth |
| RetryStorm | retries >50% of accept rate | page | retry-storm |
| DeliveryUnknownGrowth | transitions to DELIVERY_UNKNOWN rising | page | consumer-crash-ambiguous-outcomes |
| CallbackInvalidSignatureSpike | invalid HMAC signatures | page | callback-signature-failures |
| ApiErrorBudgetFastBurn / SlowBurn | multi-window burn rate for 99.9% SLO (14.4x and 6x) | page / ticket | high-api-latency |
| ApiLatencyP95High | p95 >300ms | page | high-api-latency |
| BackendDown | scrape target down 2m | page | high-api-latency |
| HikariPoolSaturation | active/max >90% or pending >5 | page | database-outage |
| JvmGcPauseHigh | GC >10% of wall time | ticket | high-api-latency |
| RedisFallbackActive | coordination falls back from Redis | ticket | redis-outage |
| BulkJobStalled | no bulk progress for 10m | ticket | bulk-job-restart |
| DiagnosticsWriteFailures | Mongo diagnostics writes failing | ticket | database-outage |

Every alert carries `runbook_url: docs/operations/runbooks/<name>.md`; the eleven runbook names are fixed and checked by the
static checks. The runbook documents themselves are authored elsewhere in `docs/operations/runbooks/`.

## Grafana
Provisioned automatically in compose (datasource uid `prometheus`, folder "Messaging Platform"). Dashboards are generated by
`node observability/grafana/build-dashboards.mjs` and committed: API and SLO, Dispatch and Queues, Providers, Bulk and Batch, JVM and DB Pool.
Open `http://localhost:13000` (admin password from `.env`). Not rendered/verified here.

## Local usage
```bash
docker compose --env-file .env -f infrastructure/compose/docker-compose.yml up -d prometheus grafana otel-collector   # NOT executed here
# Backend run locally via scripts/run-local.sh is scraped through job "messaging-backend-local" (host.docker.internal:18080).
```
Without Docker, scrape manually: `curl http://localhost:18080/actuator/prometheus | grep '^messaging_'`.

## Tuning notes
- Burn-rate windows follow the multi-window pattern: page when both a short and long window exceed the budget burn.
- Keep alert `for:` durations above the scrape/evaluation interval (15s) and above normal batch bursts.
- Label cardinality: `messaging.bulk.progress{jobId}` creates a series per job; drop old jobs or aggregate if the job count grows.
