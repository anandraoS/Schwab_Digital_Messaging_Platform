# SLIs, SLOs and Alerting

**All targets are illustrative examples for this reference implementation. They are not commitments of any organization and were not derived from measured production traffic.** Real SLOs come from stakeholder agreement and measured baselines.

## SLI / SLO table
| # | SLI | Definition (good / valid events) | Source metric | Illustrative SLO (30 d window) |
|---|---|---|---|---|
| 1 | API availability | non-5xx responses / all responses on `/api/v1/messages` POST (excluding 4xx, 503 backpressure counted as bad) | `http_server_requests_seconds_count{uri="/api/v1/messages",method="POST"}` by `status` | 99.9% |
| 2 | API latency | POST /messages under 300 ms | `http_server_requests_seconds_bucket` | 99% < 300 ms; p95 < 150 ms |
| 3 | Acceptance durability | accepted (202) messages that reach a post-VALIDATED state within 60 s of acceptance, or are scheduled in future | `messaging.transitions{to="QUEUED"}` vs `messaging.messages.accepted` | 99.99% (no accepted message lost) |
| 4 | Dispatch delay (CRITICAL/HIGH) | accepted to provider submit | `messaging.dispatch.delay` timer (priority tag if present, see source) | p95 < 5 s for CRITICAL, p95 < 30 s for HIGH, p95 < 5 min NORMAL |
| 5 | Provider submission success | attempts with outcome ACCEPTED / attempts excluding PERMANENT_FAILURE for invalid recipients | `messaging.provider.calls{outcome}` | 99% per provider (5 min rolling for alerting) |
| 6 | Delivery-status processing | callbacks processed (any result except invalid_signature) within 10 s of receipt | `messaging.callbacks{result}`, callback handler `http_server_requests_seconds` | 99.9% < 10 s |
| 7 | Backlog health | oldest queued message age | `messaging.queue.depth{priority}`, ops endpoint `oldest-message age` (`GET /api/v1/ops/queues`) | CRITICAL < 30 s, NORMAL < 15 min |
| 8 | Unresolved ambiguity | messages in DELIVERY_UNKNOWN older than 30 min | derived from `messaging.transitions{to="DELIVERY_UNKNOWN"}` minus resolutions (or DB gauge, see source) | < 0.1% of daily volume |
| 9 | DLQ | DLQ depth growth | `messaging.queue.dlq.depth`, `messaging.deadletter.total{reason}` | depth < 100 and no growth for > 1 h |

Note: delivery rate to the end handset/mailbox is mostly outside platform control; track it as an indicator, not an SLO.

## Error budget policy (example)
| Budget status | Action |
|---|---|
| > 50% remaining | normal feature work |
| 25-50% | review top contributors weekly; risky changes need extra review |
| < 25% | freeze non-reliability changes except security fixes; focus on reliability backlog |
| exhausted | freeze all releases except fixes restoring the SLO; mandatory post-incident review |
Burn-rate alerting (multi-window): page when 14.4x burn over 1 h and 5 min; ticket at 6x over 6 h and 30 min (standard multi-window pattern; thresholds illustrative).

## Alert examples (Prometheus-style; static, not loaded into a Prometheus here)
```yaml
- alert: ApiAvailabilityBurn
  expr: |
    sum(rate(http_server_requests_seconds_count{uri="/api/v1/messages",method="POST",status=~"5.."}[5m]))
    / sum(rate(http_server_requests_seconds_count{uri="/api/v1/messages",method="POST"}[5m])) > 0.0144
  for: 5m
  labels: {severity: page}
- alert: ProviderSubmissionSuccessLow
  expr: |
    sum by (provider) (rate(messaging_provider_calls_total{outcome="ACCEPTED"}[5m]))
    / sum by (provider) (rate(messaging_provider_calls_total[5m])) < 0.95
  for: 5m
  labels: {severity: page}
- alert: ProviderCircuitOpen
  expr: messaging_provider_circuit_state == 2
  for: 2m
  labels: {severity: page}
- alert: QueueBacklogGrowing
  expr: sum(messaging_queue_depth) > 1000 and deriv(sum(messaging_queue_depth)[10m]) > 0
  for: 10m
  labels: {severity: ticket}
- alert: DlqGrowth
  expr: increase(messaging_deadletter_total[15m]) > 20
  labels: {severity: ticket}
- alert: PoisonMessages
  expr: increase(messaging_deadletter_total{reason="POISON_MESSAGE"}[15m]) > 0
  labels: {severity: ticket}
- alert: CallbackSignatureFailures
  expr: increase(messaging_callbacks_total{result="invalid_signature"}[5m]) > 10
  labels: {severity: page}
- alert: RetryStorm
  expr: sum(rate(messaging_retry_scheduled_total[5m])) > 50
  for: 10m
  labels: {severity: ticket}
- alert: RedisFallbackActive
  expr: increase(messaging_redis_fallback_total[5m]) > 0
  labels: {severity: ticket}
- alert: DiagnosticsWriteFailures
  expr: rate(messaging_diagnostics_write_failures_total[10m]) > 0
  for: 30m
  labels: {severity: info}
- alert: HikariPoolSaturated
  expr: hikaricp_connections_pending > 0
  for: 3m
  labels: {severity: page}
- alert: DeliveryUnknownAging
  expr: messaging_delivery_unknown_oldest_age_seconds > 1800   # gauge name: see source; may not exist
  labels: {severity: ticket}
```
Micrometer-to-Prometheus naming: dots become underscores, counters get `_total`, timers become `_seconds_*` (verify exact names against `/actuator/prometheus` of a running instance; the shipped alert rules in `observability/alerts` are the executed-nowhere source of truth).

## Dashboard panels (suggested)
Accepted rate by channel/priority; status transitions; dispatch delay percentiles; queue depth by priority + DLQ; provider calls by outcome and circuit state; callbacks by result; retry scheduled; bulk progress; Hikari active/pending; JVM heap/GC pause; HTTP latency by route.

## Review cadence
Monthly SLO review; adjust only with data. Link: runbooks in `docs/operations/runbooks/`.
