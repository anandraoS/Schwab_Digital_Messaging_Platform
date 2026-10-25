# Question Bank 3: Distributed Messaging, Reliability and Platform Design (Q47-Q70)

Reference-implementation study material. Platform behavior cites CONTRACT section X / ADR-0001 (RabbitMQ choice). Nothing here claims execution of any code or load test.

## Platform Design and Delivery Semantics

### Q47. How would you design a notification platform for millions of customers?
**Tags:** System Design, Staff, Scenario
**Analogy:** A national postal service: a front counter that receipts every parcel, sorting centres that route it, trucks (providers) that actually deliver, and tracking slips that tell you what happened.
**Model answer:**
Clarify requirements: channels (email, SMS, push), peak and average rates (say 5 million customers, a 1 million message campaign plus 500 transactional per second), latency targets (transactional under 10 seconds to provider, campaigns hours), durability (no lost accepted message), compliance (consent, quiet hours, audit), multi-tenancy, and delivery visibility.

Capacity math: 500 msg/s transactional plus a campaign of 1,000,000 at 2,000 msg/s completes in about 500 seconds; at 1 KB per message-row write, 3,000 writes/s is within one tuned PostgreSQL primary; envelopes carry no content or PII (CONTRACT section 6), so queue payloads are tiny.

Architecture (CONTRACT sections 1, 2 and 6):
- API: `POST /messages` with mandatory idempotency key, tenant isolation, rate limits; validates and persists `message_request`, `outbox_event`, audit in one transaction; returns 202.
- Outbox publisher to RabbitMQ priority queues (ADR-0001), so security alerts overtake campaigns.
- Dispatch consumers (bounded concurrency, prefetch): dedupe, CAS claim, policy re-check, render template with pinned version, token-bucket rate limit per provider, resilient provider call, record attempt.
- Callback service: signed webhooks, dedupe, rank-ordered status updates.
- Retry with exponential backoff and jitter, DLQ, fallback channels honoring consent.
- Reconciliation for unknown outcomes; sweeper for stuck work.
- Bulk campaigns through Spring Batch with throttling.
- Observability: metrics in CONTRACT section 8, tracing and correlation ids; SLOs on accept latency and accepted-to-terminal time.

Failure modes: provider outage (circuit breaker, failover provider), broker outage (outbox buffers), DB failover, duplicate callbacks, thundering herds after quiet hours.

Scaling levers: stateless API/consumer pods, partitioning by tenant if needed, read replicas for queries, archive old data (partition by time).

Trade-off: RabbitMQ is simpler for priorities and per-message TTL, but Kafka suits replay and analytics; the design isolates the choice behind the `MessageBus` port.
**Follow-ups:**
- Where is the first bottleneck and how do you find it?
- How would you do multi-region?
- How do you keep a marketing campaign from delaying security alerts?
**Pitfalls:**
- Calling providers inline from the API.
- Claiming exactly-once.
- Ignoring consent, quiet hours and audit.

### Q48. Explain queue semantics: ack, nack, requeue, prefetch and visibility, and how they affect reliability.
**Tags:** Messaging, Intermediate, Concept
**Analogy:** A waiter takes an order slip from the kitchen window; it is only crossed off the list when the dish is served (ack), otherwise it goes back to the window (nack/requeue).
**Model answer:**
In a broker such as RabbitMQ, a consumer receives a message that remains "unacknowledged" (in flight) until it acks. If the consumer's channel closes without ack, the broker redelivers it, which is how at-least-once works.

- Ack: processing finished; broker removes the message. Ack after the durable side-effect (state saved), never before.
- Nack/reject with requeue=true: return to the queue, potentially redelivered immediately, so a persistent failure becomes a hot loop. Requeue with delay (retry exchange with TTL, delayed queues, or application-scheduled retry) instead; the platform uses retry with `next_attempt_at` and bus-level delayed redelivery (CONTRACT section 6).
- Nack with requeue=false: routes to a dead-letter exchange (DLX) if configured.
- Prefetch (QoS): the maximum number of unacked messages per consumer. Low values give fairness and bounded memory; high values increase throughput but let one slow consumer hoard messages. The contract defaults to 16 with 8 workers.
- Publisher confirms: the broker acks the publisher once the message is safely stored (and replicated for quorum queues); required for durable publishing.
- Durability: durable queues, persistent messages, quorum queues for replicated safety.
- Redelivered flag helps detect duplicates but is not reliable for deduplication.

Reliability consequences: duplicates are normal, so consumers must be idempotent (`processed_message` plus CAS state change). Unbounded retry loops need a poison threshold (CONTRACT section 6). SQS-like systems use visibility timeouts instead of connection-bound acks, with similar semantics.

Measure: unacked count, redelivery rate, ack latency, consumer utilization, queue depth and oldest message age.
**Follow-ups:**
- What happens to unacked messages on graceful shutdown?
- Why can a high prefetch hurt priority handling?
- Difference between nack and reject?
**Pitfalls:**
- Acking before the work is saved.
- Requeue loops without delay.
- Auto-ack mode for important work.

### Q49. Compare at-most-once, at-least-once and exactly-once delivery. What does "effectively once" mean here?
**Tags:** Messaging, Senior, Concept
**Analogy:** Sending a registered letter and asking for a receipt: you might send it twice if the receipt is lost, but the recipient's desk clerk only files it once.
**Model answer:**
- At-most-once: send without retry; no duplicates, but messages can be lost. Acceptable for telemetry, not for security alerts.
- At-least-once: retry until acknowledged; no loss but duplicates are possible. Default for brokers and HTTP clients with retries.
- Exactly-once: each message is processed once. Within a single system (Kafka transactions between topics, or a database transaction), it can be achieved; across independent systems with failures (broker plus database plus an external provider) it cannot be guaranteed end to end, because an acknowledgement can always be lost after the effect occurred (Two Generals problem).

"Effectively once" combines at-least-once transport with idempotent processing so that duplicates have no additional effect. The contract (CONTRACT section 6): at-least-once transport, idempotent consumer, provider idempotency ref (`messageId:attemptGroup`), equals effectively-once business processing, never exactly-once across the provider boundary.

Building blocks:
- Idempotency keys on API submission.
- Consumer dedupe table with a unique `dispatch_id`.
- Compare-and-set state transitions.
- Provider-side deduplication via an idempotency ref if the provider supports it.
- For providers without it, reconcile ambiguous outcomes instead of blind retries.

Note the residual risk: if a provider accepts a request, and we time out, a retry may cause a duplicate unless the provider dedupes; the platform chooses unknown-then-reconcile for email and SMS and retry for push with a collapse key.

Measure: duplicate rate (consumer dedupe hits, duplicate callbacks), ambiguous outcome count.
**Follow-ups:**
- Why is Kafka's exactly-once not enough for provider calls?
- How does the idempotency ref help?
- What business messages tolerate at-most-once?
**Pitfalls:**
- Advertising exactly-once.
- Idempotent consumer without a unique constraint.
- Ignoring the provider boundary.

## Duplicates, Ambiguous Outcomes and Webhooks

### Q50. How would you prevent duplicate notifications when a provider times out?
**Tags:** Reliability, Senior, Scenario
**Analogy:** You shouted an order through a noisy window and heard nothing back; you do not know if the kitchen got it, so you ask the kitchen to check before ordering again.
**Model answer:**
Clarify: which channel, does the provider support idempotency references or lookup, and what is the business cost of a duplicate versus a miss (a duplicate password reset code is tolerable; a duplicate trade confirmation is more embarrassing; a duplicate marketing blast to millions is costly)?

A timeout is ambiguous: the provider may or may not have accepted the request. Naive retry produces duplicates.

Approach (CONTRACT section 6):
1. Write the `message_attempt` row as `STARTED` before the call so a crash leaves evidence.
2. Send an `idempotencyRef = messageId:attemptGroup` so a compliant provider deduplicates retries within its window.
3. Classify the outcome as `TIMEOUT`/`AMBIGUOUS` and move to `DELIVERY_UNKNOWN`, not to retry-pending, for email and SMS.
4. Wait for a callback up to `reconcileAfter`; then `ReconciliationService` calls the provider `lookup(idempotencyRef)`. If the provider confirms not received, retry safely; if accepted, move to `SENT_TO_PROVIDER` and continue to await delivery.
5. For push, retry is allowed because a collapse key makes duplicates replace each other.

Layered defenses: consumer dedupe (`processed_message`), CAS `QUEUED to PROCESSING`, per-message attempt uniqueness (`UNIQUE(message_id, attempt_no)`).

Failure modes: provider with neither idempotency nor lookup; then choose by category: for security alerts prefer duplicate over miss (retry after a short wait), for marketing prefer miss. Make that a documented policy per category.

What I would measure: ambiguous outcome rate by provider, share resolved by callback versus lookup, duplicate complaints, time in `DELIVERY_UNKNOWN`.

<!-- in-project:b16 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/messaging/DispatchConsumer.java`
>
> ONE transaction: unique `processed_message.dispatch_id` plus compare-and-set QUEUED to PROCESSING plus the attempt row. A concurrent insert of the same `dispatchId` raises `DataIntegrityViolationException`, which is caught and treated as `DUPLICATE`. The provider call happens after this transaction, never inside it.
>
> ```java
> Claim result = tx.execute(status -> {
>     if (processed.existsByDispatchId(env.dispatchId())) {
>         return Claim.DUPLICATE;
>     }
>     ProcessedMessage pm = new ProcessedMessage();
>     pm.setDispatchId(env.dispatchId());
>     processed.saveAndFlush(pm);
>     boolean won = notifications.transition(m.getId(), MessageStatus.QUEUED, MessageStatus.PROCESSING,
>             "claimed by dispatch consumer", ACTOR);
>     if (!won) {
>         status.setRollbackOnly();
>         return Claim.LOST;
>     }
>     // markClaimed(... lockedUntil ...), insert message_attempt row with state STARTED
>     return Claim.WON;
> });
> ```

**Follow-ups:**
- What should the timeout value be?
- What if the provider's idempotency window is shorter than reconcile time?
- How do you test this?
**Pitfalls:**
- Immediate retry on timeout.
- Treating timeout as failure and falling back to another channel.
- No evidence recorded before the call.

### Q51. How would you handle a provider accepting a message but returning no response?
**Tags:** Reliability, Senior, Scenario
**Analogy:** The courier took your package but the receipt printer jammed; you cannot tell whether to send another package or just ask the courier for the tracking number.
**Model answer:**
This is the classic lost acknowledgement. Cases: the TCP connection reset after the request was sent, the read timeout fired while the provider was still processing, or the provider returned success but the response was lost.

Clarify: what does the provider's API offer: idempotency key, client reference, lookup by reference, delivery webhooks? What is the SLA for webhooks?

Approach, consistent with the platform's model (CONTRACT sections 3.1 and 6):
- Record the call as an attempt with outcome `AMBIGUOUS` or `TIMEOUT`; status `PROCESSING` to `DELIVERY_UNKNOWN`. Do not mark failed and do not re-send blindly. (As implemented, EMAIL and SMS take this path; PUSH timeouts and ambiguous answers are retried like transient failures because the provider deduplicates on the idempotency reference, DEVIATIONS stage 2, item 6.)
- Keep the `idempotencyRef` stable so any later retry is deduplicable.
- Wait for the delivery or acceptance callback (matched by our client reference or provider message id). A late `DELIVERED` receipt is applied directly: the monotonic rank rule allows it from `DELIVERY_UNKNOWN` (CONTRACT section 3.4).
- After `reconcile-after`, query the provider via `lookup`. Outcomes: found (move to `SENT_TO_PROVIDER` or `DELIVERED`), not found (safe to retry, subject to attempts), still unknown (back off and check again, eventually escalate to an operator view such as `/failed-messages`).
- A crash after acceptance but before our write is covered by the `StuckMessageSweeper`, which converts stale `PROCESSING` rows to `DELIVERY_UNKNOWN`.

Metrics and alerting: count in `DELIVERY_UNKNOWN`, age of oldest unknown, reconciliation resolution rate. Alert if unknown share of a provider exceeds a threshold because it implies provider instability.

Trade-offs: waiting delays delivery of a message that might have failed; for time-critical security alerts a business rule can allow a duplicate-tolerant retry. State this policy explicitly rather than hiding it in code.
**Follow-ups:**
- When is it safe to retry after lookup says not found?
- How long can a message remain unknown?
- What do you tell the customer-facing support tool?
**Pitfalls:**
- Marking it FAILED (customer may have received it).
- Marking it DELIVERED (no evidence).
- Looping retries forever.

### Q52. How would you prevent retries from overwhelming a downstream provider?
**Tags:** Reliability, Senior, Scenario
**Analogy:** When a shop is overwhelmed, a hundred customers all re-knocking every second only makes it worse; they should wait different, growing amounts of time.
**Model answer:**
Clarify: the provider's documented rate limit and error semantics (429, 503), our peak volume, and whether failures are provider-wide or per request.

Retry storms happen when many clients retry in lockstep after a failure, multiplying load on a struggling dependency. Controls, used together:
- Exponential backoff with jitter: `delay = min(cap, base * 2^(n-1)) * random(0.5..1.0)` (CONTRACT section 6). Jitter de-synchronizes retries.
- Bounded attempts (`messaging.dispatch.max-attempts`), then fallback or DLQ.
- Provider rate limiter (token bucket in Redis): when empty, requeue with a short delay without consuming an attempt (CONTRACT section 6). Respect `Retry-After` from the provider.
- Circuit breaker around provider calls: when the failure rate exceeds a threshold it opens, calls fail immediately, and traffic is routed to retry-pending or an alternate provider; half-open probes test recovery.
- Bulkheads: limit concurrent calls per provider so one provider cannot consume all workers.
- Retry budgets: cap retries as a fraction of total traffic (for example 10 percent) so retries never double load.
- Retry only transient classes: never retry permanent failures or ambiguous outcomes blindly.
- Shed load: backpressure and priority so critical messages still flow.
- Ramp up gradually after recovery, rather than releasing the whole backlog at once.

Layer retries carefully: retry in one place only (the consumer's retry policy), not in the HTTP client, circuit breaker and consumer simultaneously, which multiplies attempts (3 x 3 x 3 = 27 calls).

Measure: provider call rate versus limit, retry ratio, 429/503 rate, circuit state (`messaging.provider.circuit.state`), `messaging.retry.scheduled`.
**Follow-ups:**
- What is the difference between jitter variants?
- How does a circuit breaker interact with the rate limiter?
- How do you drain a large backlog safely?
**Pitfalls:**
- Fixed-interval retries.
- Retries at several layers.
- Ignoring `Retry-After`.

### Q53. How would you implement idempotent delivery callbacks (webhooks)?
**Tags:** Reliability, Senior, Scenario
**Analogy:** A courier rings your bell three times about the same parcel; you sign the delivery book once, and on the next rings you simply point at the signature.
**Model answer:**
Clarify: providers deliver webhooks at least once, with retries, and may send duplicates and out-of-order events. They may also be forged. So the endpoint must authenticate, deduplicate, order and respond quickly.

Design (CONTRACT sections 1, 3.4 and 4):
1. Authenticate: verify the HMAC-SHA256 signature `X-Signature: t=<epoch>,v1=<hex>` over `t + "." + rawBody` using constant-time comparison, check the timestamp tolerance (5 minutes), and a replay cache by `X-Event-Id` (`SignatureVerifier`, `ReplayGuard`). Verify against the raw bytes, before JSON parsing.
2. Deduplicate: a replayed `X-Event-Id` inside the replay window is answered `200 {"result":"replayed"}` before any work (not 409). Then normalize the payload (`EventNormalizer`) and insert into `delivery_event` with `UNIQUE(provider_id, provider_event_id)`; a duplicate creates no second row, answers 2xx and counts `duplicate` (`messaging.callbacks{result}`).
3. Order: apply the monotonic rank rule; a lower rank never overwrites a higher one; stale or out-of-order events are recorded with `applied=false` and an `ignored_reason`.
4. Apply the state change with compare-and-set so concurrent callbacks for one message cannot interleave unsafely.
5. Respond fast: persist then return 2xx; do heavy work asynchronously. Return 2xx for duplicates so the provider stops retrying; return 4xx only for invalid signatures.
6. Store raw payload best-effort in Mongo diagnostics; failure to write must never fail callback processing (CONTRACT section 1).

Edge cases: event for an unknown message (this platform answers `202 unknown_message` and neither stores it, because `delivery_event.message_id` is a mandatory foreign key, nor remembers it in the replay guard, so the provider's retry can succeed once the message row is committed); DELIVERED then BOUNCED conflict keeps the first terminal and audits `DELIVERY_EVENT_CONFLICT`.

Measure: callback results by type, processing latency, duplicate ratio, invalid signature rate (a security signal).

<!-- in-project:b08 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/callback/ReplayGuard.java`
>
> A bounded in-memory map in front of the `replay_guard` table (the table stays authoritative). The key is `providerId:eventId`, hashed if it would exceed the 200 character primary key. `markSeen` is called only after successful processing.
>
> ```java
> public boolean isReplay(String key) {
>     Instant cutoff = clock.instant().minus(pipeline.replayWindow());
>     Instant cached = cache.get(key);
>     if (cached != null && cached.isAfter(cutoff)) {
>         return true;
>     }
>     Optional<ReplayGuardEntry> row = repository.findById(key);
>     if (row.isPresent() && row.get().getSeenAt().isAfter(cutoff)) {
>         remember(key, row.get().getSeenAt());
>         return true;
>     }
>     return false;
> }
> ```

**Follow-ups:**
- Why verify the signature on the raw body?
- What if a callback arrives before the message row is committed?
- How do you rotate signing secrets?
**Pitfalls:**
- Dedupe via a check-then-insert.
- Returning 500 for duplicates (invites more retries).
- Trusting the network instead of verifying signatures.

## Ordering, Retry, DLQ, Backpressure and Resilience

### Q54. What ordering guarantees can a messaging system provide, and do you need ordering for notifications?
**Tags:** Messaging, Senior, Concept
**Analogy:** A single checkout lane keeps people in order; open ten lanes and the order of who finishes first is no longer guaranteed.
**Model answer:**
Ordering is expensive because it limits parallelism. Systems offer ordering within a scope:
- Kafka: total order within a partition; none across partitions. Choose a key (such as `recipientId`) so related messages share a partition.
- RabbitMQ: FIFO within a single queue with a single consumer; with multiple consumers or redelivery or priorities, order is not preserved. Priority queues explicitly reorder by priority (ADR-0001).
- Cloud queues: FIFO variants with message group ids.

Do notifications need order? Mostly no across different messages: a password reset and a marketing email to the same person are independent. Where order matters:
- The lifecycle events for the same message: sent, delivered, bounced can arrive out of order. The platform does not rely on transport order; it uses a monotonic rank plus timestamps (CONTRACT section 3.4).
- Sequences like "trade executed" then "trade settled" for the same order id: use a version or sequence number in the payload and let the consumer drop or reorder stale ones, or key by entity to a single partition.
- Retries intentionally reorder messages; accept that.

Design principle: prefer commutative, idempotent updates and version checks over strict ordering; use ordering only for narrowly scoped keys and accept the throughput cost (per-key serialization).

Measure: out-of-order event counts (`messaging.callbacks{result="out_of_order"}`), stale updates ignored.
**Follow-ups:**
- How do you get ordering per customer in RabbitMQ?
- What happens to order on consumer failure?
- How does a version field help?
**Pitfalls:**
- Assuming queue order equals processing order with many consumers.
- Using global ordering when per-key is enough.
- Ignoring that retries reorder.

### Q55. Describe a good retry policy: which failures to retry, backoff formula, jitter and limits.
**Tags:** Reliability, Intermediate, Concept
**Analogy:** If a door is locked you knock again after a pause that gets longer each time, with a little randomness so a crowd does not knock in unison.
**Model answer:**
Classify the failure first:
- Retry: transient errors (timeouts with idempotent operation, 5xx, connection reset before request sent, 429 with `Retry-After`).
- Do not retry: permanent failures (4xx validation, invalid recipient, hard bounce, authentication misconfiguration).
- Ambiguous: handle through reconciliation, not blind retry (CONTRACT section 6).

Formula used by the platform (CONTRACT section 6): `next_attempt_at = now + min(cap, base * 2^(n-1)) * jitter`, with jitter between 0.5 and 1.0. Example with base 5 s and cap 10 minutes: attempt 1 about 2.5-5 s, attempt 2 5-10 s, attempt 3 10-20 s, attempt 4 20-40 s, growing to the cap. Variants: full jitter (`random(0, delay)`) spreads best; equal jitter keeps a minimum wait; decorrelated jitter grows from the previous sleep.

Limits and bookkeeping:
- Max attempts (`messaging.dispatch.max-attempts`) and a message expiry (`expires_at`): do not deliver an OTP code 3 hours late.
- Persist `attempt_count`, `next_attempt_at`; status `RETRY_PENDING` and a scheduler or delayed redelivery moves it back to `QUEUED`.
- Exhaustion: fallback channel if allowed, else `DEAD_LETTERED(RETRIES_EXHAUSTED)`.
- Rate-limited outcomes do not consume attempts when they are our own limiter's backpressure.

Testing: unit-test the formula with a seeded random and a fixed `Clock`; property tests assert delay within bounds and monotonic cap.

Measure: retry rate per channel, attempts-to-success distribution, share exhausted.

<!-- in-project:b09 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/service/RetryPolicy.java`
>
> Defaults in `application.yml`: `backoff-base-ms: 1000`, `backoff-cap-ms: 60000`, `max-attempts: 5`. This is "equal jitter" (half fixed, half random). Only `ProviderOutcome.isRetryable()` outcomes are retried automatically.
>
> ```java
> /** Un-jittered backoff for the given (1-based) attempt number; overflow-safe. */
> public Duration ceiling(int attemptNo) {
>     int shift = Math.min(Math.max(attemptNo - 1, 0), 30);
>     long raw = baseMs * (1L << shift);
>     if (raw < 0 || raw > capMs) {
>         raw = capMs;
>     }
>     return Duration.ofMillis(raw);
> }
>
> /** Jittered backoff in [ceiling/2, ceiling]. */
> public Duration backoff(int attemptNo, RandomGenerator random) {
>     long ceil = ceiling(attemptNo).toMillis();
>     double factor = 0.5 + 0.5 * random.nextDouble();
>     return Duration.ofMillis(Math.max(1, (long) (ceil * factor)));
> }
> ```

**Follow-ups:**
- Why cap the backoff?
- Why is the retry ratio an important health signal?
- How should expiry interact with retries?
**Pitfalls:**
- Retrying everything.
- No jitter.
- No expiry for time-sensitive messages.

### Q56. How should a dead-letter queue be designed and operated?
**Tags:** Reliability, Intermediate, Concept
**Analogy:** The "undeliverable mail" shelf at the post office: it keeps the mail safe, labels why it could not be delivered, and someone must regularly go through it.
**Model answer:**
A DLQ holds messages the system cannot process automatically, so they stop blocking healthy traffic yet are not lost. Entry reasons in the platform (`DlqReason`, CONTRACT section 3.2): `RETRIES_EXHAUSTED`, `POISON_MESSAGE`, `PERMANENT_FAILURE`, `OPERATOR`.

Design:
- Dead-letter exchange and queue at the broker, plus a durable record in the database: status `DEAD_LETTERED` with the reason so operators can search and filter through `GET /api/v1/dead-letters` (CONTRACT section 5).
- Preserve context: original envelope, attempt history, last error, timestamps, correlation id.
- Operator actions: replay (`DEAD_LETTERED` to `QUEUED`, only by operator) and discard (audited). Replay after fixing the root cause, at a throttled rate, with idempotent processing so replays are safe.
- Never auto-replay endlessly; DLQ replay loops reproduce poison messages.
- Do not store sensitive content in DLQ payloads: the envelope has no content or PII by design (CONTRACT section 6).

Operations:
- Alert on DLQ depth above zero for a sustained time and on age of oldest entry; `messaging.deadletter.total{reason}` and `messaging.queue.dlq.depth`.
- Runbook: group by reason and error code; fix the code or data; replay a small batch; then the rest.
- Retention and ownership: someone owns the DLQ queue; define the SLA to review.

Pitfalls to avoid: a DLQ nobody watches is data loss with extra steps.
**Follow-ups:**
- How do you replay without re-triggering the same failure?
- What distinguishes a poison message?
- How do you prevent duplicates on replay?
**Pitfalls:**
- No alerting.
- Auto-replay loops.
- Dropping the failure reason.

### Q57. One malformed message keeps crashing the consumer and the queue is not draining. What do you do?
**Tags:** Reliability, Senior, Scenario
**Analogy:** One jammed parcel stops the sorting belt; you need to pull that parcel off the belt and set it aside so the rest keep flowing.
**Model answer:**
Clarify: how many consumers are affected, is the same message being redelivered (look at the redelivered flag and logs with a repeated message id), and how old is the oldest message.

Symptoms of a poison message: the same dispatch id appears repeatedly in logs, consumers throw before ack, requeue puts it back at the head of the queue, throughput falls to zero.

Immediate mitigation:
- Identify the message from logs (correlation id, `dispatchId`).
- Move it out: reject without requeue (to DLX) manually, or pause consumers (`POST /ops/consumers/pause`, CONTRACT section 5), purge or move the specific message, then resume.

Structural fix (CONTRACT section 6): a poison threshold. After `poisonThreshold` unexpected failures for a delivery (tracked via a redelivery count header or `processed_message` counters), the consumer sends the message to the DLQ with reason `POISON_MESSAGE`; unprocessable inputs (missing message row, deserialization failure) go straight to the DLQ with no retry. Always distinguish: transient infrastructure failure (retry with delay) versus data-caused failure (DLQ).

Prevention:
- Schema versioning and tolerant deserialization of envelopes.
- Contract tests for envelope evolution; deploy consumers before producers when adding fields.
- Validation at the producer.
- Catch `Throwable` at the consumer boundary and always ack, nack or dead-letter: never let an exception escape and loop.

Measure: `messaging.deadletter.total{reason="POISON_MESSAGE"}`, redelivery rate, consumer error rate.

<!-- in-project:b42 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/messaging/DispatchHandler.java`
>
> `DispatchConsumer.handle` dead-letters an unreadable envelope immediately (`BusDecision.deadLetter(DlqReason.POISON_MESSAGE)`), and the bus dead-letters after a poison threshold, so one bad message cannot block the queue.
>
> ```java
> /**
>  * ... (threshold, or the envelope was unreadable). Lets the consumer mirror the dead-lettering in the database.
>  * Never called for dead-letters the handler itself requested through BusDecision#deadLetter.
>  * Implementations must not throw.
>  */
> default void onPoison(DispatchEnvelope envelope, int deliveryCount, Throwable cause) {
> }
> // DlqReason: RETRIES_EXHAUSTED, POISON_MESSAGE, PERMANENT_FAILURE, OPERATOR
> ```

**Follow-ups:**
- How do you count deliveries across consumer restarts?
- How would you prevent a bad deploy from creating poison messages?
- Can quorum queues limit redeliveries?
**Pitfalls:**
- Infinite requeue.
- Ignoring non-transient classification.
- Purging the whole queue.

### Q58. What is backpressure and where does the platform apply it?
**Tags:** Reliability, Senior, Concept
**Analogy:** A sink with a small drain: if you keep pouring faster, it overflows; backpressure is the tap that slows when the sink is nearly full.
**Model answer:**
Backpressure propagates "slow down" from a saturated consumer toward producers, so queues, memory and latency stay bounded instead of the system collapsing.

Application points (CONTRACT section 6):
- API edge: when queue depth exceeds `messaging.backpressure.max-queue-depth`, `POST /messages` returns `503` with `Retry-After`, and clients back off. The per-client rate limiter (429) controls individual fairness.
- Consumer: bounded worker pool and bounded prefetch; the in-memory bus has a bounded queue (capacity 5000) that blocks or rejects publishers.
- Provider boundary: token-bucket rate limit and circuit breaker keep calls within provider capacity; when the bucket is empty the message is requeued with a short delay without consuming an attempt.
- Bulk jobs: writer-side rate limiter and chunk-based flow with bounded concurrency (CONTRACT section 7).
- Database: bounded Hikari pool; slow queries surface as pending connections, which should shed load rather than queue unboundedly.

Alternatives and trade-offs:
- Drop (load shedding) low-priority work when overloaded.
- Buffer: absorbs bursts but increases latency and hides overload; set limits and age alerts.
- Reactive streams propagate demand natively; blocking systems approximate with bounded queues.

Danger: unbounded queues. They turn a latency issue into a memory issue and a very late delivery of stale messages, which for expiring notifications is as bad as loss.

Measure: queue depth, oldest message age, 503/429 counts, in-flight, pool saturation, p99 accept latency.

<!-- in-project:b11 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/messaging/BackpressureGuard.java`
>
> `MessageController.submit` calls this first. The exception handler turns it into `503` with a `Retry-After` header; the default limit is `messaging.backpressure.max-queue-depth: 5000`. Counting due outbox rows too means a stalled publisher is also visible to the guard.
>
> ```java
> public void check() {
>     long backlog = bus.stats().totalDepth()
>             + outbox.countByStatusAndAvailableAtLessThanEqual(OutboxEvent.NEW, clock.instant());
>     if (backlog >= limits.maxQueueDepth()) {
>         long retryAfter = Math.min(30, 1 + backlog / Math.max(1, props.dispatch().concurrency() * 50L));
>         throw new BusRejectedException("Dispatch backlog is full (" + backlog + " waiting); retry later",
>                 retryAfter);
>     }
> }
> ```

**Follow-ups:**
- What should be shed first?
- How does priority interact with backpressure?
- How do clients learn the right retry delay?
**Pitfalls:**
- Unbounded queues or thread pools.
- Dropping silently without metrics.
- Only autoscaling.

### Q59. Explain the circuit breaker pattern and how to configure it around a provider call.
**Tags:** Resilience, Intermediate, Concept
**Analogy:** A household fuse: when too much current flows it trips so the house is protected, and you try the switch again after a short wait.
**Model answer:**
States: CLOSED (calls pass, failures are counted in a sliding window), OPEN (calls fail immediately with a `CallNotPermittedException` for a wait duration), HALF_OPEN (a limited number of trial calls; success closes, failure reopens). In the platform: Resilience4j circuit breaker plus time limiter around provider calls, exposed as `messaging.provider.circuit.state{provider}` (0 closed, 1 half-open, 2 open) (CONTRACT sections 1 and 8).

Key settings:
- Sliding window type and size (count-based 50 calls or time-based 30 seconds) and `minimumNumberOfCalls` so a few early failures do not trip it.
- `failureRateThreshold` (for example 50 percent) and `slowCallRateThreshold`/`slowCallDurationThreshold`, which treats latency as failure and prevents thread exhaustion.
- `waitDurationInOpenState` (for example 30 seconds) and `permittedNumberOfCallsInHalfOpenState`.
- What counts as failure: network errors, timeouts, 5xx, 429; not per-recipient permanent failures (invalid number), which say nothing about provider health.

Behavior when open: messages do not get lost and the open circuit must not burn the attempt budget. In this platform the breaker permit is reserved before the claim (`ResilientProviderInvoker.acquire`); an open circuit or exhausted half-open probes requeue the delivery on the bus after 0.1 to 5 seconds, the message stays `QUEUED` and no attempt is consumed (`docs/design/DEVIATIONS.md`, stage 2, item 5, ADR-0006). A general design alternative is `RETRY_PENDING` with backoff or an alternate provider. One circuit per provider (and ideally per endpoint), not global.

Interaction: timeout < circuit-breaker window expectations < thread pool tolerance. Combine with a bulkhead and a rate limiter; retry only outside the breaker or with awareness of it.

Test with the fault injector (`FaultInjector`, modes TRANSIENT, TIMEOUT, ALTERNATING, CONTRACT section 5).

Measure: time to open, open duration, number of rejected calls, recovery time.

<!-- in-project:b10 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/provider/ResilientProviderInvoker.java`
>
> One breaker per provider id (`byProvider` map). Provider calls run on a bounded `ThreadPoolExecutor` with an `ArrayBlockingQueue` and `AbortPolicy`, so a hung provider cannot absorb unbounded threads; the state is exported as the gauge `messaging.provider.circuit.state`.
>
> ```java
> this.breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
>         .failureRateThreshold(cfg.failureRateThreshold())
>         .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
>         .slidingWindowSize(cfg.slidingWindowSize())
>         .minimumNumberOfCalls(cfg.minimumCalls())
>         .waitDurationInOpenState(cfg.openStateWait())
>         .permittedNumberOfCallsInHalfOpenState(cfg.halfOpenCalls())
>         // callers consult state() before calling, so the breaker must leave OPEN by itself
>         .automaticTransitionFromOpenToHalfOpenEnabled(true)
>         .build());
> ```

**Follow-ups:**
- Why count slow calls?
- How do you avoid flapping?
- How does the breaker relate to a retry policy?
**Pitfalls:**
- One global breaker.
- Counting business rejections as failures.
- No `minimumNumberOfCalls`.

### Q60. How should timeouts be layered across API, consumer and provider calls?
**Tags:** Resilience, Senior, Concept
**Analogy:** Nested deadlines: the caller waits 10 seconds, so the cook must not spend 30 seconds; each inner step gets less time than the outer one.
**Model answer:**
Every network call needs an explicit timeout; defaults are often infinite or very long. Timeouts should be hierarchical: inner timeouts shorter than outer ones, so that the outer layer gets a chance to react.

Types:
- Connect timeout (short, 1-2 s), read/response timeout (based on p99 of the provider plus a margin, for example 3-5 s), and an overall deadline per attempt via the time limiter.
- Pool acquisition timeout (HTTP client and Hikari `connectionTimeout`) to fail fast when saturated.
- Consumer-level: an attempt budget so a single message cannot block a worker beyond `lock-ttl` (the lease after which the `StuckMessageSweeper` considers it stuck, CONTRACT sections 6 and 9). Keep the provider timeout well below the lock TTL.
- API-level: request timeout at the gateway greater than the server's work (which is short because the API only persists and enqueues).

Rules of thumb:
- Choose timeouts from measured latency percentiles: p99.9 plus margin, not guesses.
- Pair a timeout with a policy: a timeout on an unsafe operation is ambiguous (CONTRACT section 6), so route to reconciliation.
- Propagate deadlines downstream where possible (gRPC deadlines, custom header) to avoid doing work whose caller has gone.
- Retries multiply time: total worst case = attempts x timeout + backoff; ensure it fits the expiry of the message.

Measure: timeout counts per dependency, latency histograms, share of calls near the timeout (indicating the value is too tight).
**Follow-ups:**
- What is wrong with a 60-second default?
- How do timeouts relate to idempotency?
- How do you choose a read timeout value?
**Pitfalls:**
- Infinite default timeouts.
- Inner timeout longer than outer.
- Retrying on timeout without idempotency.

## Brokers, Reconciliation and Failure Scenarios

### Q61. How would you choose between Kafka and RabbitMQ?
**Tags:** Messaging, Staff, Scenario
**Analogy:** RabbitMQ is a smart post office that routes each letter and removes it once delivered; Kafka is a durable, replayable library of ordered logbooks that many readers can read at their own pace.
**Model answer:**
Clarify the workload: task distribution versus event streaming, need for replay or reprocessing, throughput (messages per second and total retained volume), ordering, routing complexity, priorities and per-message TTL, team operational experience, and whether multiple independent consumer groups need the same events.

RabbitMQ strengths: flexible routing (exchanges, bindings), per-message TTL and dead-lettering, priority queues, push-based consumption with prefetch, per-message ack and requeue, simple ops at moderate scale, quorum queues for safety. Weaknesses: messages are removed once consumed (no replay), throughput lower than Kafka at extreme scale, large backlogs hurt, ordering weak with competing consumers.

Kafka strengths: very high throughput, durable replicated log with retention, replay and reprocessing, consumer groups, ordering per partition, stream processing, exactly-once within Kafka. Weaknesses: no per-message priorities or TTL, no per-message nack/delay (needs retry topics), partition and rebalancing operational complexity, head-of-line blocking per partition when a message is slow.

Decision for this platform (ADR-0001, CONTRACT section 1): RabbitMQ, because dispatch is task-queue work needing priority queues (security alerts over campaigns), delayed retries and DLQ, and simple operations; Kafka is preferable when you need replay, stream analytics or huge fan-out of delivery events to many consumers. A hybrid is legitimate: RabbitMQ for dispatch and Kafka (or an event stream) for the delivery-event firehose to analytics. The `MessageBus` port limits switching cost.

Measure: throughput target at p99 latency, backlog recovery time, operational burden (on-call load).
**Follow-ups:**
- How would you implement delayed retries on Kafka?
- When would the platform migrate to Kafka?
- What about SQS or Pub/Sub?
**Pitfalls:**
- Choosing by popularity.
- Using Kafka as a priority queue.
- Ignoring operational cost.

### Q62. Compare the transactional outbox, change data capture and two-phase commit for publishing events reliably.
**Tags:** Messaging, Senior, Concept
**Analogy:** The outbox is a tray on your desk; CDC is a clerk who reads your diary and forwards every entry; two-phase commit is a meeting where everyone must agree before anyone acts.
**Model answer:**
Problem: update a database and publish a message atomically (the dual-write problem).
- Two-phase commit (XA/JTA): coordinates DB and broker; correct in theory but slow, couples availability of all parties, many brokers and cloud services do not support it. Generally avoided.
- Transactional outbox: write the business change and an `outbox_event` row in one local transaction; a relay publishes unsent rows and marks them. Guarantees at-least-once publication; consumers must be idempotent. Simple, portable (works on PostgreSQL, Oracle, H2). Costs: polling load and latency (the platform polls every 200 ms with CAS claims, CONTRACT section 6), table growth requiring retention.
- Change data capture: a tool (such as Debezium) reads the database log and publishes outbox rows (or table changes) to the broker. Lower latency and no polling queries, but extra infrastructure and operational complexity, and log-retention coupling.
- Listen-to-yourself or event-sourcing variants exist too.

Selection: for a moderate-scale platform with a relational store, the outbox plus poller is a pragmatic first choice; adopt CDC when polling cost or latency becomes a problem.

Implementation details: partial ordering by `created_at`; claim batches with CAS or `SKIP LOCKED`; keep payload small (ids, not content); idempotent publish keys; dead-letter bad rows; prune after retention.

Measure: outbox lag (oldest NEW age), publish rate, retries, table size.
**Follow-ups:**
- How do multiple relays avoid duplicates?
- How does the outbox preserve ordering?
- What does the consumer need to do?
**Pitfalls:**
- Calling it exactly-once.
- Publishing inside the transaction.
- Never pruning.

### Q63. Messages sit in SENT_TO_PROVIDER for hours with no delivery receipts. How would you design reconciliation?
**Tags:** Reliability, Senior, Scenario
**Analogy:** An auditor compares your outgoing parcel log with the courier's records at day's end and fixes any entries that disagree.
**Model answer:**
Clarify: is it all providers or one, is the callback endpoint receiving anything (`messaging.callbacks{result}`), and how do receipts normally arrive (webhook push, pull API, batch file)? Missing receipts can come from provider outage, broken webhook configuration (firewall, expired secret, wrong URL), signature failures, or carrier-level silence.

Reconciliation design (CONTRACT sections 2, 3.1 and 6):
- Detection: the `StuckMessageSweeper` finds `SENT_TO_PROVIDER` older than `deliveryReceiptTimeout` and marks `DELIVERY_UNKNOWN` (a distinct, honest state).
- Resolution: `ReconciliationService` calls `lookup(providerMessageId/clientRef)` in batches, rate-limited so it does not become a retry storm; applies the result using the same rank rules as callbacks (idempotent): `DELIVERED`, `FAILED`, or still unknown. Late callbacks keep working at any time.
- Provider reports: for providers offering daily delivery reports or event exports, import and diff against our records (count and id level), producing exceptions for investigation.
- Operator tooling: `GET /failed-messages` for unknown and stuck items, with filters by provider and age; ability to requeue after confirmation of non-receipt.
- Terminal policy: after a maximum age, mark with a terminal "unknown, not confirmed" reason where the business accepts that (some channels like SMS never give receipts for some carriers); this is a product decision.

Failure modes: reconciliation applying stale provider data over newer callback state: enforce rank and timestamps. Overloading the provider: throttle queries.

Measure: count and age of unknown, reconciliation resolution rate, mismatches found per day, callback coverage ratio per provider.

<!-- in-project:b43 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/service/ReconciliationService.java`
>
> Checks are spaced by `reconcile-after` and bounded by `reconcile-max-checks`; afterwards the message stays visible in `GET /failed-messages` for an operator. Every check is audited as `RECONCILIATION_CHECK`.
>
> ```java
> /**
>  * Resolves DELIVERY_UNKNOWN messages by asking the provider what it knows about the idempotency reference
>  * (messageId:attemptGroup, ADR-0010). ... Lookup results: DELIVERED and FAILED are applied (with a delivery_event
>  * recorded as evidence); ACCEPTED leaves the message unknown (the receipt will come); NOT_RECEIVED triggers a safe
>  * re-queue as a new attempt with the same idempotency reference (FAILED then QUEUED, both table-legal, in one
>  * transaction); a failed or unsupported lookup changes nothing.
>  */
> @Scheduled(fixedDelayString = "${messaging.pipeline.scheduler.reconcile-interval-ms:15000}", initialDelay = 7000)
> ```

**Follow-ups:**
- Which receipt delays are normal per channel?
- How do you detect webhook delivery problems early?
- What should be shown to customer support?
**Pitfalls:**
- Leaving messages in SENT forever.
- Reconciling without idempotency.
- Treating missing receipt as failure and resending.

### Q64. Callbacks arrive out of order: BOUNCED before DELIVERED, or SENT after DELIVERED. How do you keep state correct?
**Tags:** Reliability, Senior, Scenario
**Analogy:** Postcards from a trip arrive in a random order; you use the dates and the importance of each news item to rebuild the true story.
**Model answer:**
Clarify: which events conflict and what the business wants shown to users. Provider webhooks are asynchronous and retried independently, so order is not guaranteed.

Rules from CONTRACT section 3.4:
- Dedupe key `(provider_id, provider_event_id)` unique.
- Ranks: `PROVIDER_ACCEPTED`/`SENT` and `DEFERRED` rank 1; `DELIVERED` rank 3; `BOUNCED`/`FAILED`/`REJECTED` rank 3 (terminal-negative). A lower rank never overwrites a higher rank. A `SENT` after `DELIVERED` is recorded but ignored (`applied=false`, `ignored_reason=OUT_OF_ORDER`).
- A `DELIVERED` arriving while status is `PROCESSING`, `SENT_TO_PROVIDER` or `DELIVERY_UNKNOWN` is applied.
- Conflict between terminal events (DELIVERED then BOUNCED later): keep the first terminal state and audit `DELIVERY_EVENT_CONFLICT`; operators examine it. Real-world cause: asynchronous bounces after a mailbox accepted the message.
- Use the event's `occurred_at` against the current status timestamp: older events are recorded as stale.

Implementation: single-statement CAS update `WHERE id=? AND rank(status) < :newRank` or version check, so concurrent callbacks for one message cannot interleave; all raw events remain in `delivery_event` for audit and replay. Never delete events. The decision logic is a pure function, unit-tested with every pair of event types and orders (a table-driven or property-based test over permutations).

Measure: counts by `ignored_reason`, conflicts per provider, share of messages needing manual review.

<!-- in-project:b06 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/domain/MessageStateMachine.java`
>
> The monotonic rank rule: low-rank receipts (accepted, sent) never move a message backwards, a terminal receipt that overtakes the persisted `SENT_TO_PROVIDER` is applied from `PROCESSING`, and DELIVERED then BOUNCED is a `CONFLICT` that keeps the first outcome.
>
> ```java
> public static ReceiptResolution resolveReceipt(MessageStatus current, DeliveryEventType type) {
>     if (type.rank() < 3) {
>         return ReceiptResolution.IGNORE_LOWER_RANK;
>     }
>     if (current == DELIVERED) {
>         return type == DeliveryEventType.DELIVERED
>                 ? ReceiptResolution.IGNORE_LOWER_RANK : ReceiptResolution.CONFLICT;
>     }
>     boolean receivable = current == PROCESSING || current == SENT_TO_PROVIDER || current == DELIVERY_UNKNOWN;
>     // ...
>     return type == DeliveryEventType.DELIVERED ? ReceiptResolution.APPLY_DELIVERED
>             : ReceiptResolution.APPLY_FAILED;
> }
> ```

**Follow-ups:**
- Why not trust arrival time instead of occurred_at?
- How do you treat OPENED and CLICKED?
- What if clocks differ across providers?
**Pitfalls:**
- Last-write-wins on status.
- Deleting ignored events.
- Not auditing conflicts.

### Q65. How do priority queues work, and how do you avoid starvation of low-priority messages?
**Tags:** Messaging, Intermediate, Concept
**Analogy:** An airport fast lane for urgent passengers: it helps them, but if the fast lane is always full, regular passengers never board unless there is a fairness rule.
**Model answer:**
RabbitMQ priority queues (`x-max-priority`, the platform uses 9, CONTRACT sections 1 and 3.2) deliver higher-priority messages first among those waiting. Priorities map from `CRITICAL(9)`, `HIGH(6)`, `NORMAL(3)`, `LOW(1)`.

Caveats:
- Priority only matters when a backlog exists; with idle consumers everything flows immediately.
- Prefetch interacts: consumers that prefetched 16 low-priority messages hold them while a critical one waits behind them. Keep prefetch modest.
- Many priority levels cost CPU and memory in the broker; use a handful.
- Starvation: continuous high-priority load can starve low priority indefinitely.

Mitigations:
- Rate-limit or cap high-priority traffic per tenant/client (only trusted categories like security alerts may use `CRITICAL`), enforced at the API (authorization of priority and category).
- Dedicated consumer capacity: separate queues per class and reserve a worker share (for example 70/30 weighted consumption).
- Aging: periodically boost the priority of old messages, or enforce an SLA-based escalation.
- Separate infrastructure for bulk: campaigns through the batch path with throttling, so they rarely compete.

Alternatives: separate queues per priority with weighted polling gives clearer control and observability (`messaging.queue.depth{priority}`, CONTRACT section 8) than a single priority queue; in Kafka, use separate topics.

Measure: queue depth and wait time per priority, oldest message age by priority, share of capacity per class.
**Follow-ups:**
- How would you allow tenants to buy a higher priority tier?
- What is the downside of too many priority levels?
- How do delayed retries interact with priority?
**Pitfalls:**
- Letting every client choose CRITICAL.
- High prefetch defeating priorities.
- No fairness mechanism.

### Q66. A consumer pod crashes immediately after the provider accepted the email but before the database update. What happens, and how is it handled?
**Tags:** Reliability, Staff, Scenario
**Analogy:** You posted the letter and fainted before writing it in your diary; when you wake up the diary says "not sent", yet the letter is on its way.
**Model answer:**
Clarify what evidence exists at crash time: the attempt row `STARTED` written before the call (CONTRACT section 6), the message row `PROCESSING` with `locked_until`, and the unacked broker message.

Sequence after the crash:
1. The broker redelivers the unacked message to another consumer.
2. The new consumer dedupes: `processed_message` insertion and the CAS `QUEUED to PROCESSING` fail because the row is already `PROCESSING` (lock not yet expired) so the consumer acks or skips safely without calling the provider, preventing an immediate duplicate.
3. When `locked_until` passes, the `StuckMessageSweeper` finds `PROCESSING` with a stale lock and moves it to `DELIVERY_UNKNOWN`, not retry (CONTRACT section 6).
4. Resolution: the delivery callback (matched by provider message id or client ref) usually arrives and applies `DELIVERED` or a failure from `DELIVERY_UNKNOWN`. If nothing arrives, `ReconciliationService` queries the provider using the stable `idempotencyRef`, then either confirms acceptance or safely retries.

Design choices and trade-offs:
- Lock TTL must exceed the provider timeout plus margin, otherwise a slow but alive consumer is considered dead and a second consumer starts a duplicate.
- Writing the attempt first costs one extra write but turns a silent crash into a detectable state.
- For push, a retry with a collapse key is acceptable.

Test: failure-recovery test killing the consumer between provider call and persist using the simulator and a fault hook (a failure-recovery test suite is part of the repository layout, CONTRACT section 11).

Measure: stuck-to-unknown conversions, resolution time, duplicates reported.

<!-- in-project:b26 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/scheduler/StuckMessageSweeper.java`
>
> Class Javadoc, lightly condensed. The lock deadline defaults to `lock-ttl: 2m`. After the sweep, `ReconciliationService` asks the provider what it knows about the idempotency reference.
>
> ```java
> /**
>  * Repairs the two states a crash or a lost receipt can leave behind (ADR-0010), always towards
>  * DELIVERY_UNKNOWN and never by blindly retrying:
>  *  - PROCESSING with an expired locked_until: the consumer died after claiming (possibly after the
>  *    provider accepted). The attempt row stays STARTED so reconciliation can see it.
>  *  - SENT_TO_PROVIDER older than messaging.dispatch.receipt-timeout: no delivery receipt arrived.
>  * Idempotent and multi-instance safe: each move is a compare-and-set, the loser simply skips.
>  */
> ```

**Follow-ups:**
- How do you pick `lock-ttl`?
- What if the provider has no lookup API?
- How does graceful shutdown reduce this risk?
**Pitfalls:**
- Immediate retry by the next consumer.
- No pre-call evidence.
- Lock TTL shorter than the provider timeout.

### Q67. RabbitMQ becomes unavailable for ten minutes. What happens to accepted messages and how do you recover?
**Tags:** Reliability, Senior, Scenario
**Analogy:** The conveyor belt stops, but the packing table (database outbox) keeps a list of everything waiting, so when the belt restarts nothing is forgotten.
**Model answer:**
Clarify: partial or full outage, cluster node failure with quorum queues versus total loss, and whether publisher confirms and durable queues are enabled.

Behavior by design (CONTRACT sections 1 and 6):
- API: `POST /messages` still succeeds, because it writes only to the database (message plus outbox row) and does not talk to the broker. Accepted work is durable.
- Outbox publisher: publishing fails; rows stay `NEW`; it retries with backoff. Outbox lag grows; alert on oldest NEW age.
- Backpressure: if the outbox grows beyond configured thresholds, new submissions may be limited with 503 to avoid unbounded growth (the configured threshold is queue depth, so also monitor outbox backlog).
- Consumers: lose connection; Spring AMQP reconnects automatically; in-flight unacked messages are redelivered after reconnect; idempotent processing handles duplicates.
- On recovery: publisher drains the outbox; apply rate controls and priority so critical messages go first; avoid a stampede on providers (provider limiter and gradual ramp).

Broker data loss scenario: if the broker lost its data (non-durable queues), messages already in `QUEUED` state but not consumed are gone from the broker while the database says `QUEUED`. Recovery: a sweeper/replay finds `QUEUED` rows older than a threshold without progress and re-enqueues them (`ReplayService`, `StuckMessageSweeper`), safe due to idempotent consumers.

Prevention: quorum queues with replication, publisher confirms, durable messages, multiple availability zones, tested failover.

Measure: outbox lag, publisher confirm failures, broker connection state, queue depth after recovery, time to drain.
**Follow-ups:**
- Why not make the API publish directly to the broker?
- How do quorum queues differ from classic mirrored queues?
- How do you test broker outage?
**Pitfalls:**
- Publishing directly from the request with no fallback.
- Non-durable messages.
- No process to re-enqueue stranded QUEUED rows.

### Q68. A provider recovers from an outage and the backlog of 800,000 messages causes it to fall over again. How do you drain safely?
**Tags:** Reliability, Staff, Scenario
**Analogy:** After a power cut, you switch appliances on one by one; switching everything on at once trips the fuse again.
**Model answer:**
Clarify: what the provider can handle (documented rate), message expiry (some messages are no longer worth sending), priorities, and whether customers are waiting on time-critical items.

Plan:
1. Triage before draining: expire stale messages (`expires_at`), keep security and transactional first, decide campaign fate (cancel, defer, or send later). Cancellation applies to `VALIDATED`, `QUEUED` and `RETRY_PENDING` (CONTRACT section 3.1).
2. Ramp up: raise the provider rate limit gradually (`PUT /providers/{id}/config`, CONTRACT section 5), for example 10 percent steps every few minutes while watching error rates and latency; the token bucket guarantees the cap regardless of consumer count.
3. Circuit breaker half-open probing prevents a full flood at the first sign of recovery.
4. Priority: ensure `CRITICAL` and `HIGH` messages are consumed before `LOW` backlog.
5. Add jitter to retry-pending messages so they do not all become due at the same instant.
6. Consider a secondary provider for overflow where contracts allow; keep consent rules identical.
7. Communicate with stakeholders: estimated drain time = backlog / sustained rate (800,000 / 500 per second is about 27 minutes).

Failure modes: provider throttles or blocks us; reputation damage from dumping old campaigns; duplicates because earlier timeouts left ambiguous messages that were actually accepted.

Measure: drain rate, provider 429/5xx, `messaging.dispatch.delay`, queue age, retry ratio.
**Follow-ups:**
- Should expired messages be marked rejected or failed?
- How do you prevent a bulk campaign from using all capacity?
- Who decides business triage?
**Pitfalls:**
- Releasing everything at once.
- Sending stale time-sensitive content.
- Ignoring priority during the drain.

### Q69. Redis becomes unavailable. What should happen to idempotency checks and rate limiting?
**Tags:** Resilience, Senior, Scenario
**Analogy:** If the electronic ticket scanner breaks, ushers switch to checking paper tickets: slower and less perfect, but the show goes on.
**Model answer:**
Clarify what Redis is used for: idempotency fast-path, token-bucket provider rate limit, API rate limit (CONTRACT section 1). The principle: Redis is an accelerator and coordinator, not the source of truth; its failure must not stop message processing or violate correctness.

Design (`ResilientCoordination`, CONTRACT section 1):
- Idempotency: the database table `idempotency_record` with `UNIQUE(tenant_id, idem_key)` is authoritative; Redis only avoids database hits for hot replays. On Redis failure, go straight to the DB; correctness unchanged, latency up. Counter `messaging.redis.fallback` increments.
- Provider rate limiting: fall back to an in-memory token bucket per instance. The aggregate limit becomes `perInstanceRate x instances`, which may exceed the provider's limit, so configure the fallback rate as `globalRate / expectedInstances`, or conservative, and accept lower throughput during the outage. Alternatively use a DB-based limiter at low rate.
- API rate limiting: per-instance fallback with a documented looser guarantee.
- Timeouts: short Redis client timeouts (for example 50-100 ms) and a circuit breaker around Redis so the failure does not add latency to every request.
- Recovery: automatically return to Redis; buckets start full or empty? Prefer starting empty-ish to avoid a burst.

Failure modes: split-brain limits, cache stampede on the DB when Redis is flushed (protect with bounded DB pool and the unique constraint).

Measure: fallback counter, Redis latency/error rate, DB load during the fallback, provider 429s.

<!-- in-project:b36 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/coordination/ResilientCoordination.java`
>
> The cooldown is the "breaker around Redis" from the answer. Real Redis was not executed; `InMemoryCoordination` is what the local profile and the tests use.
>
> ```java
> /**
>  * Decorator: uses the primary (Redis) and falls back to the local in-memory implementation when the primary throws.
>  * After a failure the primary is skipped for cooldown so a Redis outage does not add latency to each request.
>  * Every fallback increments messaging.redis.fallback. Degraded mode is per-instance (limits are no longer
>  * global), which is the accepted trade-off versus failing requests.
>  */
> public class ResilientCoordination implements CoordinationService {
>     private final AtomicLong primaryBlockedUntil = new AtomicLong(0);
> ```

**Follow-ups:**
- Why not make Redis mandatory?
- What consistency does a Redis lock give?
- How would you test the fallback?
**Pitfalls:**
- Failing requests when Redis is down.
- Using Redis as the only idempotency store.
- No timeout or breaker on the Redis client.

### Q70. One large tenant's campaign slows everyone else's notifications. How do you provide fairness in shared queues?
**Tags:** Multi-tenancy, Staff, Scenario
**Analogy:** A shared kitchen where one big catering order blocks every other table; separate prep stations and a fair rota fix it.
**Model answer:**
Clarify: which resource is shared and saturated (provider rate, consumer workers, DB, queue), whether tenants have contracted quotas, and the difference in importance between transactional and bulk traffic.

Layers of isolation:
- Admission control: per-tenant API rate limits and daily quotas; bulk uses a dedicated endpoint with a required scope `messages:bulk` and its own limits (CONTRACT section 5).
- Priority and category: campaigns use `LOW`/`NORMAL`, while security alerts use `CRITICAL`; priority queues keep them moving (ADR-0001).
- Throttling bulk: Spring Batch writer-side rate limiter (`rate_limit_per_sec`) so campaigns trickle in rather than flood the queue (CONTRACT section 7).
- Per-tenant provider budget: split the provider token bucket into tenant sub-buckets (guaranteed share plus burst from unused capacity), preventing one tenant from consuming the whole provider rate.
- Queue topology: shard queues per tenant tier (premium, standard, bulk) with weighted consumers; extreme isolation per tenant is expensive.
- Bulkheads: concurrency caps per tenant in consumers.

Trade-offs: strict isolation wastes capacity; work-conserving schemes (weighted fair queueing with borrowing) use idle capacity but add complexity. Start with admission control plus priority, add per-tenant buckets when measurements show noisy neighbor effects.

Failure modes: starvation of small tenants by head-of-line blocking within a single queue; hot tenants overwhelming one partition.

Measure: per-tenant throughput, queue wait time by tenant and priority, rate-limit rejections by tenant, noisy-neighbor index (p95 delay of others during a bulk run).
**Follow-ups:**
- How do you handle a tenant that needs a hard SLA?
- Where do you store quota configuration?
- How would you simulate noisy neighbors?
**Pitfalls:**
- One shared FIFO for all traffic.
- Letting tenants choose their own priority.
- Quotas that are not observable.
