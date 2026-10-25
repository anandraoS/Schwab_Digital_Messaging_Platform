# Skill: Diagnose Queue Backlog

**When**: `messaging.queue.depth` or oldest-message age rising. **Load**: `context/observability.md`, `docs/operations/runbooks/queue-backlog.md`.

## Steps
1. Snapshot: `GET /api/v1/ops/queues` (depth per priority, in-flight, consumers, DLQ depth, retry-pending, oldest age).
2. Classify using Little's law: arrival rate (`rate(messaging_messages_accepted_total)`), service rate = concurrency / provider latency.
   - arrivals > service: producers (bulk?) or consumers too slow.
   - in-flight saturated + provider latency up: downstream slow: do not add consumers.
   - in-flight 0: paused consumers, dead scheduler/poller, or broker connectivity.
3. Check provider outcomes and circuit state; check DB pool; check pause flag audit.
4. Choose the action from the runbook table (stop bulk, resume, scale within provider limits, restart stuck instance).
5. Verify trend and SLI; record in PIR if SLO impacted.

## Output
Diagnosis note: classification, evidence (metric names/values), action, verification.

## Guardrails
Never purge queues; never raise concurrency beyond provider/DB headroom without evidence.
