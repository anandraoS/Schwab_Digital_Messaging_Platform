# ADR-0013: Priority aging to prevent starvation

Status: **Proposed. Not implemented in code** (2026-10-18). `specs/0001-priority-aging.md` is a worked specification example; no class, property, metric or test implements it. If it is ever built, this ADR is accepted first and DEVIATIONS.md records any divergence.

## Context
Priorities `CRITICAL(9)`, `HIGH(6)`, `NORMAL(3)`, `LOW(1)` map to broker priorities (ADR-0001). With strict priority a sustained stream of higher-priority messages gives LOW (and under heavy HIGH load NORMAL) no upper bound on waiting time. The risk is a design analysis, not a measured incident. The current implementation uses strict priority ordering in both `InMemoryMessageBus` and the RabbitMQ topology (`infrastructure/rabbitmq/definitions.json`).

## Decision (proposed)
Introduce an *effective priority* that rises one level per configured age threshold, capped at HIGH, while the requested priority stays immutable (reporting) and CRITICAL is only reachable by request. A scheduler promotes eligible QUEUED / RETRY_PENDING messages idempotently (at most one promotion per message per level). Feature flag `messaging.dispatch.aging.enabled`, default `false`, so behaviour is unchanged when off.

## Alternatives considered
| Option | Why not (for now) |
|---|---|
| Strict priority only (today) | unbounded LOW latency under sustained HIGH load |
| Separate queues with weighted consumers | more topology and capacity tuning; fits better with Kafka-style partitions |
| Rate-limit HIGH producers | pushes the problem to clients and does not bound LOW wait |

## Consequences
- Positive (if built): bounded wait for every priority, observable and reversible.
- Negative: promotion needs a bus-level re-publish or a second queue (broker priorities are fixed at publish time), which risks duplicate envelopes; the consumer's dedupe (`processed_message`, ADR-0004) already tolerates them.
- Risk: promotion storms after an outage; mitigate with per-run caps.

## Validation
None yet: unimplemented. The spec lists the intended tests (`specs/0001-priority-aging.md`).
