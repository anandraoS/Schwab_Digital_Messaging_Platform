# Context: Observability
Load when: adding metrics, logs, traces, alerts, dashboards. Authority: CONTRACT section 8; `docs/operations/slis-slos.md`.

Metrics (exact names; Micrometer, Prometheus converts dots to underscores and adds `_total`):
`messaging.messages.accepted{channel,priority,tenant}`, `messaging.transitions{from,to}`, `messaging.dispatch.delay` (timer), `messaging.provider.calls{provider,channel,outcome}`, `messaging.provider.latency{provider}`, `messaging.provider.circuit.state{provider}` (0 closed, 1 half-open, 2 open), `messaging.queue.depth{priority}`, `messaging.queue.inflight`, `messaging.queue.dlq.depth`, `messaging.retry.scheduled{channel}`, `messaging.deadletter.total{reason}`, `messaging.callbacks{result}` (accepted, duplicate, invalid_signature, replayed, out_of_order, stale), `messaging.bulk.progress{jobId}`, `messaging.idempotency.hits`, `messaging.ratelimit.rejected{scope}`, `messaging.redis.fallback`, `messaging.diagnostics.write.failures`. Plus `hikaricp_*`, `jvm_*`, `http_server_requests_seconds`.

Rules:
- All custom meters live in `metrics.MessagingMetrics`; never create ad-hoc meter names.
- Tag cardinality: no message ids, recipient ids, or free text as tags (exception: `jobId` on bulk gauge, bounded by active jobs; remove on completion).
- Logs: JSON, MDC `correlationId`, `tenant`, `messageId`; never bodies, full addresses, secrets, signatures, tokens; log at WARN/ERROR with error codes; no stack traces for expected business rejections.
- Tracing: Micrometer OTel bridge, OTLP endpoint configurable (disabled locally); propagate correlation id; spans around provider calls and DB-heavy operations.
- New failure mode => metric + alert + runbook entry.
- Ops endpoints: `/api/v1/ops/queues`, `/api/v1/ops/summary`, `/api/v1/providers`.
