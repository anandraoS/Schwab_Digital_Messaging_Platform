# Runbooks

Runbooks are written from the design (not from real incidents). Commands use placeholders: set `BASE` to the API base URL (local default `http://localhost:18080`) and `TOKEN` to a bearer token for an OPERATOR or PLATFORM_ADMIN principal from your environment (locally via `POST /dev/token`). Do not paste real tokens into tickets or chats.

| Runbook | Trigger |
|---|---|
| [provider-outage.md](provider-outage.md) | Provider circuit open, submission success low |
| [queue-backlog.md](queue-backlog.md) | Queue depth or oldest-message age growing |
| [dlq-growth.md](dlq-growth.md) | DLQ depth growing |
| [retry-storm.md](retry-storm.md) | Retry rate high, load amplification |
| [callback-signature-failures.md](callback-signature-failures.md) | `invalid_signature` spike |
| [database-outage.md](database-outage.md) | DB unreachable or pool exhausted |
| [redis-outage.md](redis-outage.md) | `messaging.redis.fallback` incrementing |
| [bulk-job-stuck.md](bulk-job-stuck.md) | Bulk job not progressing |
| [bulk-job-restart.md](bulk-job-restart.md) | `BulkJobStalled` alert; stop/restart a FAILED/STOPPED/orphaned bulk job |
| [high-api-latency.md](high-api-latency.md) | API error-budget burn, p95 latency alert, backend down (entry point to the next row) |
| [consumer-crash-ambiguous-outcomes.md](consumer-crash-ambiguous-outcomes.md) | Consumers died; DELIVERY_UNKNOWN growing |
| [provider-rate-limit.md](provider-rate-limit.md) | Provider returns RATE_LIMITED |
| [high-latency-and-resource-saturation.md](high-latency-and-resource-saturation.md) | High API latency, GC, thread starvation, CPU saturation |

Common first steps (all incidents):
1. Declare severity, open a channel, note start time (UTC).
2. `GET $BASE/api/v1/ops/queues` and `GET $BASE/api/v1/ops/summary` for the current picture.
3. `GET $BASE/api/v1/providers` for circuit and fault state.
4. Capture a `correlationId` from any failing request for trace/log search.
5. After resolution: fill `../post-incident-review-template.md`.

Standard structure per runbook: Symptoms and alerts, Impact, Diagnose, Mitigate, Recover, Verify, Escalate, Prevent.
