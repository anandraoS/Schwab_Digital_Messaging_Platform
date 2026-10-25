# Messaging and Distributed Systems

A reference chapter for distributed messaging interviews. The reference implementation (CONTRACT sections 1, 3, 6 and 7; ADR-0001 for the broker choice) is the worked example. It is inspired by an enterprise messaging role and does not reproduce any employer's internal systems. Provider behaviour described here is general industry knowledge, and nothing in this chapter says a provider or broker was actually called or run.

## Kid-friendly analogies

| Concept | Analogy |
|---|---|
| At-least-once delivery | A teacher who keeps calling your name until you answer "here". You might answer twice if you were slow, but you will never be skipped. |
| Idempotent consumer | A light switch labelled "turn ON". Pressing it twice leaves the light on, not extra on. |
| Outbox pattern | Writing a letter and putting it in the "to mail" tray at your desk at the same moment you record it in your diary. A helper later takes letters from the tray to the post box. You never end up with a diary entry but no letter, or the reverse. |
| Retry with backoff and jitter | If a door is locked, you knock, wait a bit, knock again after a longer wait. If a hundred kids all wait the exact same time, they all knock together, so each adds a little random wait. |
| Dead-letter queue | A "lost property" box for letters nobody can deliver, so they stop blocking the mail. |
| Circuit breaker | A fuse in your house. When too much goes wrong, it trips so the wiring does not burn, then later lets a little power through to test. |
| Ambiguous outcome | You shout an order across a noisy room and hear no answer. The order may have been heard or not. Ordering again may bring two pizzas; asking "did you get my order?" is safer. |
| Backpressure | A full bus driver saying "next one, please" rather than squeezing in more people. |

## Delivery semantics

| Guarantee | Meaning | Reality |
|---|---|---|
| At-most-once | Never duplicates, may lose | Fire-and-forget; unacceptable for important notices |
| At-least-once | Never loses, may duplicate | Default for brokers with acknowledgement |
| Exactly-once | One effect | Achievable only within a closed system (for example a transactional stream pipeline); not across an external provider |

CONTRACT section 6 states the position plainly: at-least-once transport plus an idempotent consumer plus a provider idempotency reference gives effectively-once business processing, and never exactly-once across the provider boundary. That is the answer to give in an interview, with the reasoning: any acknowledgement can be lost, so the sender cannot distinguish "not processed" from "processed, ack lost".

## Idempotent consumer

The consumer must tolerate redelivery. Techniques:

| Technique | How | Platform example |
|---|---|---|
| Dedupe table | Insert a unique key before processing; duplicate insert means skip | `processed_message` with `dispatch_id` unique (CONTRACT section 4) |
| Compare-and-set state | Update status only if the current state is as expected | `QUEUED to PROCESSING`; the loser acks and skips (CONTRACT section 6) |
| Natural idempotency | Operation is a set, not an increment | Marking a state, upserting by key |
| Provider idempotency reference | Pass a stable reference so the provider can dedupe | `idempotencyRef = messageId:attemptGroup` (CONTRACT section 6) |

The envelope carries a `dispatchId` per (message, attempt number) (CONTRACT section 6) so redelivery of the same attempt is detectable while a genuine retry gets a new identity. It also carries no content and no personal data (`DispatchEnvelope`), which keeps broker payloads low-risk and small; the consumer loads data from the database.

Order of operations matters: dedupe check and state change should be atomic (one local transaction or a single conditional update). Acking before processing risks loss; acking after processing risks duplicates; choose the latter and make processing idempotent.

<!-- in-project:d01 -->
> [!NOTE] In this project
> **File:** `backend/src/main/resources/db/migration/V4__bulk_audit_idempotency_outbox.sql`
>
> Four unique constraints implement the four idempotency layers: API key, message-level key, consumer dispatch id and provider event id. The database, not application code, decides every race.
>
> ```sql
> CREATE TABLE processed_message (
>     id            VARCHAR(36)  NOT NULL,
>     dispatch_id   VARCHAR(100) NOT NULL,
>     message_id    VARCHAR(36)  NOT NULL,
>     processed_at  TIMESTAMP    NOT NULL,
>     CONSTRAINT pk_processed_message PRIMARY KEY (id),
>     CONSTRAINT uq_processed_dispatch UNIQUE (dispatch_id)
> );
> -- V3__messages.sql:  CONSTRAINT uq_msg_idempotency UNIQUE (tenant_id, idempotency_key)
> -- V4:                CONSTRAINT uq_idempotency      UNIQUE (tenant_id, idem_key)
> -- V3:                CONSTRAINT uq_delivery_event   UNIQUE (provider_id, provider_event_id)
> ```


## Outbox pattern

The dual-write problem: updating the database and publishing to a broker are two systems, so a crash between them loses or duplicates events. The outbox writes the event to a table in the same local transaction as the state change; a publisher relays rows to the broker.

```text
BEGIN
  insert message_request (status = VALIDATED)
  insert outbox_event   (status = NEW)
  insert audit_event
COMMIT
-- later, OutboxPublisher (scheduled, claim batches via compare-and-set):
publish to MessageBus -> mark outbox_event SENT -> message status QUEUED
```

CONTRACT section 6 and section 4 define exactly this: `OutboxPublisher` runs every 200 ms and claims batches with a compare-and-set update, so several instances can run without a separate distributed lock (the contract explicitly avoids ShedLock). The publisher may publish and then crash before marking SENT, producing a duplicate publish; the idempotent consumer absorbs it. Alternatives: change data capture from the outbox table (Debezium) for lower latency and no polling; the polling approach is simpler to operate.

<!-- in-project:d02 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/scheduler/OutboxPublisher.java`
>
> `available_at` lets deferred and scheduled messages (quiet hours) wait in the outbox until due. A requeue or replay writes a new outbox row, so the publisher publishes whether the message is VALIDATED or already QUEUED (DEVIATIONS stage 1).
>
> ```java
> @Scheduled(fixedDelayString = "${messaging.pipeline.scheduler.outbox-interval-ms:200}", initialDelay = 1000)
> public void tick() { ... publishBatch(); ... }
>
> List<OutboxEvent> due = outbox.findByStatusAndAvailableAtLessThanEqualOrderByCreatedAtAsc(OutboxEvent.NEW,
>         clock.instant(), PageRequest.of(0, Math.max(1, pipeline.scheduler().outboxBatch())));
> ```


## Ordering

Global ordering is expensive and rarely needed. Ask what ordering the business requires: usually per entity (per message, per recipient).

| Broker feature | Ordering behaviour |
|---|---|
| Kafka | Strict order within a partition; choose the partition key (for example recipient or message id) |
| RabbitMQ | Order within a single queue and single consumer; competing consumers, redelivery, requeue and priorities all break strict order |

The platform uses priority queues (`x-max-priority=9`, CONTRACT section 1, `Priority{CRITICAL(9),...}`), which by design reorder messages, so it does not promise FIFO across priorities. Instead, correctness comes from state design: delivery callbacks may arrive out of order, so the contract uses a monotonic rank rule (DELIVERED rank 3 never overwritten by SENT rank 1; stale or out-of-order events are recorded with `applied=false` and an ignored reason, CONTRACT section 3.4). Pattern to cite: make handlers commutative and monotonic rather than depending on delivery order.

## Retry, backoff and jitter

Retry only transient failures. A permanent failure (invalid address, hard bounce) must not be retried.

```text
delay = min(cap, base * 2^(attempt-1)) * random(0.5, 1.0)
```

That is the contract's formula (CONTRACT section 6): exponential growth, a cap, and jitter to avoid synchronised retry storms ("thundering herd"). Full jitter (`random(0, delay)`) spreads load most but can retry very quickly; the 0.5 to 1.0 factor keeps a floor. Parameters live in `messaging.dispatch.backoff-base-ms` and `backoff-cap-ms` (CONTRACT section 9).

| Outcome | Action (CONTRACT section 6) |
|---|---|
| Transient failure, rate limited | `RETRY_PENDING` with computed `next_attempt_at`; fallback or dead-letter when exhausted |
| Permanent failure | `FAILED`; fallback if consent allows; hard bounce adds suppression |
| Local rate limiter empty | Requeue with a short delay without consuming an attempt |

Other considerations: set a retry budget (maximum attempts and total age), honour the message `expires_at`, honour provider `Retry-After`, and retry at one layer only; stacking client, library and broker retries multiplies traffic.

Delayed redelivery in RabbitMQ is commonly built from per-message TTL plus a dead-letter exchange, which is the "per-message TTL/DLX" capability cited in ADR-0001 (CONTRACT section 1).

## Dead-letter queues and poison messages

A poison message always fails (bad payload, missing referent). Without a limit it loops forever, blocking a consumer and burning CPU. The contract sends envelopes to the DLQ with `DlqReason.POISON_MESSAGE` after `poisonThreshold` repeated unexpected exceptions, and `RETRIES_EXHAUSTED`, `PERMANENT_FAILURE` or `OPERATOR` for the other paths (CONTRACT sections 3.2 and 6).

Operating a DLQ: alert on depth and oldest age, record the reason, give operators inspect, replay and discard actions (the contract exposes `/dead-letters`, replay and discard, with audit, CONTRACT section 5), and make replay safe through the same idempotent consumer. A DLQ nobody watches is a silent data-loss mechanism.

## Backpressure

When producers outrun consumers, queues grow without bound. Options: bound everything (bounded executor queues, broker prefetch, queue length limits), slow the producer, shed load, or scale consumers.

The contract bounds consumer concurrency (default 8), prefetch (default 16), and in-memory queue capacity, and returns 503 plus `Retry-After` at the API when depth exceeds `messaging.backpressure.max-queue-depth` (CONTRACT section 6). RabbitMQ prefetch (QoS) limits unacknowledged messages per consumer: too high gives unfair distribution and large redelivery bursts; too low wastes throughput on round trips. Scaling consumers is pointless if the real bottleneck is the provider rate limit, which the platform enforces with a token bucket (CONTRACT section 1).

Also drain gracefully on shutdown: stop accepting, finish in-flight work, return unacked messages (CONTRACT section 6).

## Circuit breaker

States: closed (normal), open (fail fast), half-open (trial calls). Triggers: failure rate or slow-call rate over a sliding window. Combine with a time limiter and bulkhead so a slow provider cannot consume all threads. The contract wraps provider calls in Resilience4j (circuit breaker plus time limiter) through `ResilientProviderInvoker` and exposes `messaging.provider.circuit.state{provider}` (0 closed, 1 half-open, 2 open, CONTRACT section 8).

Important details: define which exceptions count as failures (a 4xx for a bad address is not provider ill-health), fall back sensibly (route to another provider or channel, or schedule retry), and test the open state, since breakers that never trip in tests often misbehave in production. When open, messages should wait in `RETRY_PENDING` rather than burn attempts.

## Ambiguous outcomes and reconciliation

A timeout after sending a request to a provider is the hard case: the provider may or may not have accepted. Blindly retrying an SMS or email can send duplicates to a customer; not retrying can lose a notice.

CONTRACT section 6 specifies the policy:

| Situation | Behaviour |
|---|---|
| `TIMEOUT` or `AMBIGUOUS` outcome on EMAIL or SMS | Move to `DELIVERY_UNKNOWN`; wait for a callback until `reconcileAfter` |
| After the wait | `ReconciliationService` calls provider `lookup(idempotencyRef)`; if the provider confirms not received, retry is safe |
| PUSH | Retry allowed; the collapse key makes duplicate pushes idempotent |
| Crash after provider accepted, before persisting | Attempt row written `STARTED` before the call; the `StuckMessageSweeper` moves stale `PROCESSING` rows to `DELIVERY_UNKNOWN`, not to retry |

The principle: write intent before acting, and when the outcome is unknown, find out rather than guess. Late callbacks can resolve DELIVERY_UNKNOWN to DELIVERED or FAILED (CONTRACT section 3.1 transition table). Present this as a decision about the cost of a duplicate versus the cost of a miss, which differs by channel and by category (a security alert and a marketing message weigh differently).

## Kafka versus RabbitMQ

| Dimension | RabbitMQ | Kafka |
|---|---|---|
| Model | Broker routes messages to queues (exchanges, bindings), consumers are pushed messages | Distributed append-only log, consumers pull and track offsets |
| Retention | Message removed after ack | Retained by time or size; replay by rewinding offsets |
| Ordering | Per queue | Per partition |
| Priorities, per-message TTL, DLX, delayed redelivery | Built in or idiomatic | Not native; build with retry topics |
| Throughput and fan-out | Good; lower than Kafka at extreme scale | Very high; many independent consumer groups cheaply |
| Operations | Simple to start | More moving parts (brokers, partitions, consumer groups, KRaft) |
| Best fit | Task queues, work distribution, priority and delay semantics | Event streams, replay, analytics, very large fan-out |

ADR-0001 chose RabbitMQ for priority queues, per-message TTL and DLX, simple operations and push-based consumers, while noting Kafka is preferable for replay, stream analytics and huge fan-out (CONTRACT section 1). That framing, a choice made against stated requirements with the tradeoff named, is what interviewers look for. Behind a `MessageBus` port, with `RabbitMessageBus` and `InMemoryMessageBus` (CONTRACT section 2), the decision is reversible. Note the in-memory bus is a test and local substitute with the same semantics, not a production claim.

## Channel specifics

### Acceptance versus delivery

"Provider accepted" and "delivered" are different facts. For email, acceptance by an API or SMTP relay (a 2xx or `250`) means the provider queued it; delivery to the recipient's mail server, inbox placement, and later bounces or complaints arrive asynchronously. SMS carriers return delivery receipts (DLRs) that can be delayed, missing or unreliable. Push providers accept a message for a device token without confirming the device displayed it. The contract encodes this with `SENT_TO_PROVIDER` (never implies delivery), `DELIVERED` on a receipt, `DELIVERY_UNKNOWN` after `receipt-timeout`, and delivery event types such as `BOUNCED`, `DEFERRED`, `OPENED`, `CLICKED` (CONTRACT sections 3.1 and 3.2). Open and click tracking are unreliable signals (image blocking, link scanners, privacy proxies); do not drive critical logic from them.

### Email authentication: SPF, DKIM, DMARC

| Mechanism | What it does |
|---|---|
| SPF | DNS record listing the servers allowed to send for a domain, checked against the envelope sender |
| DKIM | Cryptographic signature over headers and body, verified with a public key published in DNS (selector) |
| DMARC | Policy (`none`, `quarantine`, `reject`) requiring SPF or DKIM to pass and align with the visible From domain; provides aggregate reporting |

Roll out DMARC gradually (`p=none` with reports, then enforce). Use a dedicated subdomain for marketing mail to isolate reputation from transactional mail. Handle bounces: hard bounces (permanent, invalid address) lead to suppression, soft bounces (mailbox full, deferred) lead to retry; process complaints (feedback loops) as suppression. The contract's suppression list blocks all categories on a channel after a hard bounce or complaint (CONTRACT section 3.3 item 2). Include `List-Unsubscribe` headers on marketing mail.

### SMS: GSM-7, UCS-2 and segments

| Encoding | Single segment | Concatenated segment | When used |
|---|---|---|---|
| GSM-7 | 160 characters | 153 characters per part | Basic Latin set plus a few extras; some characters (such as `{`, `}`, `[`, `]`, `^`, `~`, `|`, `\`, and the euro sign) take two septets |
| UCS-2 (UTF-16) | 70 characters | 67 characters per part | Any character outside GSM-7 (emoji, many non-Latin scripts, curly quotes) |

One non-GSM character in a message switches the entire message to UCS-2, shrinking the limit and increasing cost and segment count. The template layer should preview segment count (the contract provides a `POST /templates/preview` endpoint, CONTRACT section 5) and normalise "smart quotes". Also: use E.164 phone numbers (stored as `phone_e164`, CONTRACT section 4), respect carrier and country sender-ID rules, and handle opt-out keywords such as STOP and HELP according to local regulation. Throughput limits per number or short code are real, which is why the contract includes a per-provider token bucket.

### Push: APNs and FCM tokens

Device tokens identify an app installation on a device and change over time. APNs (Apple) and FCM (Firebase Cloud Messaging) report invalid or unregistered tokens in their responses; the sender must remove those tokens rather than retrying them, which maps to PERMANENT_FAILURE handling. Tokens are credentials-like identifiers: store them as sensitive data and avoid logging them. Payload size limits are small (on the order of a few KB; confirm current provider documentation). `collapseKey` (FCM) or `apns-collapse-id` replaces a pending notification with a newer one, and `ttlSeconds` bounds how long the provider holds it for an offline device; both appear in the contract's `channelOptions.push` (CONTRACT section 5). Priority maps to provider priority classes, with high priority intended for time-sensitive user-visible alerts, not marketing.

### Consent and quiet hours (stated generally)

General principles: marketing generally requires opt-in and honours opt-out; transactional and security notices are treated differently from marketing; quiet hours are evaluated in the recipient's time zone; suppression lists override everything; fallbacks must re-check consent for the new channel. Exact legal requirements differ by jurisdiction and channel and need legal review; the platform's rules are in CONTRACT section 3.3 (opt-in for MARKETING only, quiet hours default 21:00 to 08:00 with deferral rather than dropping, and "fallback never bypasses consent"). Treat the rules as configuration, keep an audit trail of decisions, and avoid storing more personal data than needed (the contract stores template variables rather than rendered bodies, and masks addresses in API responses).

## Observability for messaging

Instrument the lifecycle: accepted, queued, dispatch delay, provider calls by outcome, retries, dead letters, callbacks by result (CONTRACT section 8 lists these exact names). Propagate a correlation id across API, outbox, broker headers, logs and traces so one message can be followed end to end. Alert on symptoms: oldest message age, DLQ growth, circuit open, delivery success rate drop, callback signature failures.

## Key takeaways

- Assume at-least-once delivery; make the consumer idempotent with a dedupe key and compare-and-set transitions; state clearly that exactly-once does not exist across an external provider.
- Use the outbox pattern to avoid the database-plus-broker dual write; publisher duplicates are handled by the idempotent consumer.
- Do not depend on global ordering; make state handling monotonic and commutative so late or duplicate events are harmless.
- Retry only transient errors, with exponential backoff, a cap and jitter, at one layer, within a budget; route exhausted messages to a DLQ that is monitored and replayable.
- Bound queues, concurrency and prefetch; apply backpressure at the edge with 503 and `Retry-After`.
- Use circuit breakers with time limits to protect threads and providers, and count only genuine provider-health failures.
- For ambiguous outcomes write intent first, then reconcile by lookup instead of guessing; weigh duplicate cost against miss cost per channel.
- RabbitMQ suits task queues with priority, TTL and DLX; Kafka suits replay, streams and large fan-out; hide the choice behind a port.
- Acceptance is not delivery; know SPF, DKIM, DMARC, GSM-7 versus UCS-2 segmenting, push token invalidation, and treat consent and quiet hours as policy applied before every send, including fallbacks.
