# ADR-0001: Asynchronous messaging, RabbitMQ vs Kafka

Status: Accepted (2026-10-18)

## Context
`POST /api/v1/messages` must return `202 Accepted` quickly and decouple the caller from slow, failure-prone providers. Dispatch needs: four priority levels (`CRITICAL(9)`, `HIGH(6)`, `NORMAL(3)`, `LOW(1)`), per-message delay for retry backoff, dead-lettering, bounded consumer concurrency, and simple operations. Volumes are modelled as bursty (campaign bulk jobs next to latency-sensitive security alerts). Messages are small envelopes (`DispatchEnvelope`: ids only, no content, no PII).

## Decision
Use **RabbitMQ** as the production transport behind the `MessageBus` port (`messaging` package). Two implementations: `RabbitMessageBus` (profile `rabbit`, topology in `RabbitTopologyConfig` and `infrastructure/rabbitmq/definitions.json`) and `InMemoryMessageBus` (profiles `local`, `test`) with identical ack/nack/requeue-with-delay/DLQ semantics. Business code never imports RabbitMQ types.

Why RabbitMQ for this workload:
- Native priority queues (`x-max-priority=9`) so a SECURITY_ALERT is not stuck behind a marketing bulk job.
- Dead-letter exchanges and per-queue/per-message TTL give delayed retry and DLQ without a separate scheduler.
- Push-based consumers with `prefetch` give natural bounded concurrency and backpressure.
- Work-queue semantics (competing consumers, ack/nack) match "dispatch one message once".

## Alternatives considered
| Option | Pros | Cons | Verdict |
|---|---|---|---|
| Kafka | Replayable log, huge fan-out, stream analytics, strong ordering per partition | No per-message priority, no native delayed retry (needs retry topics), consumer-group rebalancing complexity, head-of-line blocking per partition | Better for event streaming of delivery events; not for this dispatch queue |
| Amazon SQS / cloud queue | Managed | Vendor coupling, priority via multiple queues, no local parity | Possible adapter behind `MessageBus` |
| DB-as-queue only (poller) | No broker | Polling latency, DB contention at scale | Used only for the outbox and scheduled sends, not the hot path |
| ActiveMQ/Artemis | JMS standard | Less ops tooling familiarity | Not chosen |

When to revisit: if consumers need to replay history by offset, or if delivery-event analytics become a first-class stream, introduce Kafka as a *second* sink fed from the outbox, not as a replacement for the dispatch queue.

## Consequences
- Positive: priority handling and delayed retry are broker features; simple topology; local runs need no broker.
- Negative: no historical replay (replay is DB-driven via `ReplayService` from `DEAD_LETTERED`/`FAILED`); the broker is a stateful dependency to operate (quorum queues recommended in prod, see `infrastructure/rabbitmq`).
- Risks: broker-DB divergence (mitigated by ADR-0009 outbox and ADR-0004 consumer dedupe); priority queues degrade with very large backlogs (monitor `messaging.queue.depth{priority}`).
- Delivery semantics: at-least-once transport; effectively-once business processing (see `docs/architecture/delivery-semantics.md`).

## Validation
- `InMemoryMessageBus` and `RabbitMessageBus` share a contract test (see source `messaging` test package). Rabbit-backed tests are `*ContainerIT` in profile `-Pcontainers` and are **not executed** in this environment.
- Metrics: `messaging.queue.depth{priority}`, `messaging.queue.inflight`, `messaging.queue.dlq.depth`.
