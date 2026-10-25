---
mode: agent
description: Diagnose a growing queue backlog using ops endpoints and metrics; propose actions without changing code
---
A queue backlog is suspected. Inputs: ${input:evidence:paste ops/queues output, metric values, recent changes (no secrets, no real data)}.

Follow `ai-engineering/skills/diagnose-queue-backlog.md` and `docs/operations/runbooks/queue-backlog.md`.

1. Classify: arrival rate vs service rate (concurrency divided by provider latency). Is it producers (bulk job), consumers (paused, idle, stuck), or downstream (provider slow or rate-limited, circuit open, DB pool)?
2. List the exact metrics/endpoints needed to confirm each hypothesis (`GET /api/v1/ops/queues`, `messaging.queue.depth`, `messaging.queue.inflight`, `messaging.provider.calls{outcome}`, `messaging.provider.latency`, `hikaricp_connections_pending`, `messaging.dispatch.delay`).
3. Recommend ordered actions with risk for each. Never recommend purging queues. Do not recommend adding consumers when the provider is slow or rate-limited.
4. State what evidence would change your conclusion.
5. Draft the incident note fields for `docs/operations/post-incident-review-template.md`.

Do not edit files. Do not claim you observed a live system.
