# Runbook: High API Latency, Error-Budget Burn, Backend Down

Entry point for the alerts `ApiErrorBudgetFastBurn`, `ApiErrorBudgetSlowBurn`, `ApiLatencyP95High` and `BackendDown` (`observability/prometheus/alerts.yml` references this file name). The detailed triage tree lives in [high-latency-and-resource-saturation.md](high-latency-and-resource-saturation.md); this page only adds the first minutes per alert.

| Alert | First checks | Then |
|---|---|---|
| `ApiErrorBudgetFastBurn` / `SlowBurn` (5xx ratio) | `GET $BASE/api/v1/ops/summary`; look at `http_server_requests_seconds_count{status=~"5.."}` by `uri`; check for 503 `SERVICE_UNAVAILABLE` (database unreachable or backpressure: the API fails closed and sends `Retry-After`) | `database-outage.md` if the database is the cause; `queue-backlog.md` if backpressure (`messaging.backpressure.max-queue-depth`) is the cause; otherwise the triage tree |
| `ApiLatencyP95High` | compare p95 by `uri`; `hikaricp_connections_pending`; GC pauses; provider latency | triage tree in `high-latency-and-resource-saturation.md` |
| `BackendDown` (target not scraped) | is the process/pod running and ready (`/actuator/health/readiness`)? recent deployment? scrape path/network? | restart one instance at a time; if a release caused it, `../rollback-plan.md` |

Clients can safely retry 5xx/503 on `POST /api/v1/messages` with the same `Idempotency-Key` (ADR-0004). Fill `../post-incident-review-template.md` afterwards. This runbook is written from the design, not from a real incident.
