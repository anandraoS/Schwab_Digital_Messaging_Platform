# 7. Queue-Based Dispatch

## 7.1 Why a queue at all

A provider call takes hundreds of milliseconds and sometimes fails. If the API made that call, request latency would be the provider's latency, an outage would be an API outage, and a traffic spike would hit the vendor unthrottled. A queue turns the spike into a backlog the platform drains at the pace the providers allow. The cost is that delivery is asynchronous and at-least-once, which the rest of the design handles with the outbox, idempotent consumers and the state machine.

The kid-friendly version: the clerk puts letters in an outgoing tray. The couriers take them one at a time, urgent ones first. If the tray overflows, the clerk politely tells new customers to come back in a moment (HTTP 503 with `Retry-After`) instead of letting letters fall on the floor.

## 7.2 The transactional outbox

The dangerous gap in naive designs is the moment between "committed to the database" and "published to the broker". A crash there loses the message; publishing first and committing second can publish a message that never existed. The platform closes the gap with the **transactional outbox pattern** (ADR-0009): the submit transaction writes an `outbox_event` row, and a scheduled `OutboxPublisher` moves rows to the bus.

{{diagram:outbox}}

Implemented details that matter (DEVIATIONS, stage 1 and 2):

- The publisher selects `status=NEW AND available_at <= now`, in batches of 100, every 200 ms (`messaging.pipeline.scheduler.outbox-interval-ms`, `outbox-batch`).
- It **claims** the row (`markSent` compare-and-set), moves `VALIDATED` to `QUEUED` (never failing if the message is already `QUEUED`, which happens for requeue and replay) and publishes, all in one transaction. A failed publish (backpressure, broker down) rolls everything back, so the row stays `NEW` and is retried: **at-least-once** publication.
- Cancelled messages are skipped by the publisher (`status` not `VALIDATED` or `QUEUED`).
- An envelope can overtake the commit of its own `QUEUED` transition. The consumer then sees a message still `VALIDATED` and requeues it for 150 ms (`validated-requeue-delay`), at most 40 deliveries, then dead-letters it.

## 7.3 The MessageBus port

```java
public interface MessageBus {
    void publish(DispatchEnvelope envelope);                       // BusRejectedException on backpressure
    void publishDelayed(DispatchEnvelope envelope, Duration delay);// retry backoff, rate-limit requeue
    void subscribe(DispatchHandler handler);
    QueueStats stats();
    void pause(); void resume(); boolean isPaused();
    void start(); void stop(); boolean isRunning();
}
```

The handler returns a `BusDecision` that drives **ack**, **requeue with delay** or **dead-letter**. Two adapters implement the port with the same semantics:

| Adapter | Profile | Properties |
|---|---|---|
| `InMemoryMessageBus` | `local`, `test` | Bounded priority queue (capacity 5000), worker threads, requeue-with-delay, in-memory DLQ; unit tested by `InMemoryMessageBusTest`, `GracefulShutdownTest` |
| `RabbitMessageBus` | `rabbit` (group of `prod-like`) | Priority queue (`x-max-priority` 9), TTL delay tiers, dead-letter exchange, manual ack, prefetch 16; topology in `infrastructure/rabbitmq/definitions.json` and `RabbitTopology` |

{{diagram:busTopology}}

> [!WARNING] Rabbit code was not executed
> There is no broker on the authoring machine. Only the pure parts (`RabbitBusSupportTest`) and the topology versus `definitions.json` (`RabbitTopologyTest`) run in the default build. `RabbitMessageBusContainerIT` compiles under `-Pcontainers` but was not run. With Rabbit, only the total ready count of the dispatch queue is available, so `GET /ops/queues` reports it under `NORMAL`; delays are rounded up to the next TTL tier (5 s, 30 s, 2 m, 10 m) and longer delays finish with an `x-not-before` header.

### Priorities

`Priority{CRITICAL(9), HIGH(6), NORMAL(3), LOW(1)}`. Higher weights are delivered first. Bulk jobs default to lower priorities so they cannot starve urgent traffic; aging (spec `specs/0001-priority-aging.md`) is the planned starvation protection for `LOW`.

## 7.4 The dispatch consumer

{{diagram:dispatch}}

### Order of operations as implemented

The design documents describe the consumer as "claim, then check". The implementation reverses the first half, for a concrete reason (DEVIATIONS, stage 2, item 1): a requeue because of the rate limiter reuses the same `dispatchId`, so it must not be blocked by the dedupe table and must not consume an attempt.

1. **Pre-claim checks** (no state change): cancel or status, expiry, policy, provider selection, circuit state, provider token bucket. Failing the token bucket or an open breaker requeues the delivery for 0.1 to 5 seconds without consuming an attempt.
2. **The claim** is one transaction: unique `processed_message` row, `QUEUED -> PROCESSING` compare-and-set, `locked_until = now + lock-ttl`, `attempt_count` increment, and the `message_attempt` row inserted as `STARTED`. Losing the race means acknowledge and skip.
3. **The provider call** runs outside any database transaction, under `ResilientProviderInvoker`.
4. **The outcome** is applied in a new short transaction, with the attempt row finished.

The circuit-breaker permit is **reserved before the claim** (`ResilientProviderInvoker.acquire`) and released again if the message is not claimed, is rate limited or fails to render. A recovering provider therefore never burns the attempt budget of messages that merely met a refusing breaker.

### Outcome mapping

{{diagram:consumerDecision}}

| Outcome | Result | Notes |
|---|---|---|
| `ACCEPTED` | `SENT_TO_PROVIDER`, provider message id stored | Receipt callback finishes the story |
| `TRANSIENT_FAILURE`, `RATE_LIMITED` | `RETRY_PENDING`, `next_attempt_at = now + min(cap, base x 2^(n-1)) x jitter`; exhausted attempts go to fallback or `DEAD_LETTERED(RETRIES_EXHAUSTED)` | Chapter 13 |
| `PERMANENT_FAILURE` | `FAILED`, fallback if allowed; a hard bounce adds a suppression | |
| `TIMEOUT`, `AMBIGUOUS` | EMAIL and SMS: `DELIVERY_UNKNOWN`; PUSH: retried like a transient failure (the provider deduplicates on the idempotency reference) | Chapter 12 |
| Poison envelope | Dead-letter with `POISON_MESSAGE` after `poison-threshold` (5) deliveries; unreadable bodies immediately | Chapter 13 |

## 7.5 Backpressure and graceful shutdown

- **Backpressure.** `BackpressureGuard.check()` runs at the top of `MessageController.submit`. When the bus depth plus due outbox rows reach `messaging.backpressure.max-queue-depth` (5000) the API answers 503 `SERVICE_UNAVAILABLE` with `Retry-After`; the outbox publisher also sees `BusRejectedException` and keeps rows `NEW`.
- **Bounded consumers.** `concurrency` 8 workers per instance, `prefetch` 16 (Rabbit prefetch, or in-memory queue handoff).
- **Graceful shutdown.** `SmartLifecycle` stops intake, waits up to `shutdown-timeout` (30 s) for in-flight work; unacknowledged deliveries return to the queue and the next consumer dedupes through `processed_message` and the CAS. A hard kill leaves a `PROCESSING` row that `StuckMessageSweeper` converts to `DELIVERY_UNKNOWN` (Chapter 12).
- **Pause and resume.** `POST /api/v1/ops/consumers/pause|resume` (PLATFORM_ADMIN) stops handing deliveries to workers while publishing continues; the backpressure and backlog-recovery tests use this.

## 7.6 Tests that prove it

| Test | Proves |
|---|---|
| `InMemoryMessageBusTest` | Priority order, ack, requeue with delay, DLQ, bounded capacity |
| `GracefulShutdownTest` | In-flight work finishes, unacked deliveries return |
| `DispatchFlowIT`, `ChannelDispatchIT` | Message flows to `SENT_TO_PROVIDER` for each channel |
| `IdempotentConsumerIT` | Duplicate envelopes cause one provider call |
| `ConcurrencyIT` | Racing consumers cause exactly one provider call |
| `BackpressureIT` | 503 plus `Retry-After`, no loss of accepted messages |
| `RabbitTopologyTest` | Declared topology equals `definitions.json` |

## Reference material

{{embed:docs/architecture/dispatch-retry-flow.md|title=Dispatch and retry flow (design document)|note=Implemented order differs in the details listed in section 7.4 and in DEVIATIONS (pre-claim checks, claim as one transaction, outbox-based quiet-hours deferral).}}

{{embed:docs/adr/0001-asynchronous-messaging-rabbitmq-vs-kafka.md|title=ADR-0001: RabbitMQ versus Kafka}}

{{embed:docs/adr/0009-outbox-pattern.md|title=ADR-0009: Outbox pattern}}

{{embed:specs/0001-priority-aging.md|title=Spec 0001: Priority aging}}
