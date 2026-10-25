# System Design Walkthroughs

Four whiteboard walkthroughs grounded in the reference implementation's decisions: the message lifecycle (CONTRACT section 3.1), transactional outbox (CONTRACT section 6), RabbitMQ as primary bus (ADR-0001), PostgreSQL as primary store (ADR-0005), Redis for idempotency fast-path and rate limiting (CONTRACT section 1). This is a reference design for interview practice. Nothing here claims any of it was executed, and capacity figures are worked arithmetic, not measurements.

Framework used in each: requirements, capacity, API, data, flow, failure modes, trade-offs, metrics, follow-ups (see `00-how-to-use.md` section 5.2).

---

## Walkthrough 1: Notification platform for millions of customers

**Analogy:** a post office. Senders drop letters at the counter and get a receipt immediately (HTTP 202). Sorters route each letter to the right truck (channel and provider). A tracking slip follows every letter, and "handed to the courier" is not the same as "delivered".

### 1.1 Requirements

Functional:
- Accept send requests for EMAIL, SMS, PUSH with template and variables, per tenant.
- Respect consent, suppression, and quiet hours (CONTRACT section 3.3).
- Track every message through the lifecycle, expose status and history.
- Retry transient failures, fall back to another channel only when consent allows.
- Accept provider delivery callbacks.

Non-functional:
- 10 million customers, average 3 messages per customer per day.
- Accept path p99 under 200 ms, because it only validates and persists.
- Dispatch delay for CRITICAL and HIGH under 5 s at p95. Marketing may take minutes.
- Availability 99.95 percent for accept. No message loss after a 202. No duplicate sends beyond what the provider boundary makes unavoidable.
- Multi-tenant isolation. PII minimised (envelopes carry no content, CONTRACT section 6).

Out of scope: content authoring UI, provider contracts, analytics warehouse.

### 1.2 Capacity math

| Quantity | Calculation | Result |
|---|---|---|
| Messages per day | 10,000,000 x 3 | 30,000,000 |
| Average rate | 30,000,000 / 86,400 s | about 347 per second |
| Peak rate (10x average, market-open bursts) | 347 x 10 | about 3,500 per second |
| Envelope size | about 300 bytes | 3,500 x 300 = about 1 MB/s on the bus |
| In-flight at peak (Little's law) | rate x latency = 3,500 x 0.2 s (provider p50 200 ms) | about 700 concurrent provider calls |
| Pods at 8 threads (reference default `consumer.concurrency`) | 700 / 8 | about 88 pods (too many) |
| Pods at 50 threads | 700 / 50 | 14 pods, so raise concurrency per pod, bound by DB pool |
| Row footprint per message | request 1 KB + 1.3 attempts x 300 B + 2 events x 300 B | about 2 KB |
| Storage per day | 30,000,000 x 2 KB | about 60 GB |
| Hot retention 30 days | 60 GB x 30 | about 1.8 TB (partition by day or month, archive after) |
| DB write rate | per message: 1 request insert, 1 outbox insert, about 4 status updates, 1 attempt insert, 2 events = about 9 writes | 3,500 x 9 = about 31,500 writes per second at peak, so shard or batch outbox claims and keep rows narrow |

Takeaway: the database write path, not the queue, is the first bottleneck. Mitigations: narrow rows, batched outbox claims, status updates by compare-and-set, partitioning by time, read replicas for query APIs.

### 1.3 API

```
POST /api/v1/messages              Idempotency-Key required, 202 Accepted + Location
GET  /api/v1/messages/{id}/status  lightweight poll
GET  /api/v1/messages/{id}/history lifecycle timeline
POST /api/v1/messages/{id}/cancel  only VALIDATED, QUEUED, RETRY_PENDING
POST /api/v1/callbacks/{providerId} HMAC-signed provider webhook
```

Semantics: 202 means "durably accepted", not "sent". Same key and same request hash returns the original response with `Idempotent-Replay: true`. Same key with a different hash is 422 `IDEMPOTENCY_KEY_REUSED`. Body limit 256 KB. Per-client rate limit with `Retry-After` on 429 (CONTRACT section 5).

### 1.4 Data model

| Table | Key columns | Notes |
|---|---|---|
| `message_request` | id, tenant_id, recipient_id, channel, category, priority, status, scheduled_at, next_attempt_at, version | Unique (tenant_id, idempotency_key). Indexes (tenant_id, status, created_at DESC), (status, next_attempt_at) |
| `message_attempt` | message_id, attempt_no, provider_id, outcome | Unique (message_id, attempt_no). Written STARTED before provider call |
| `outbox_event` | id, aggregate_id, status NEW or SENT | Written in the same transaction as the message |
| `processed_message` | dispatch_id unique | Consumer dedupe |
| `delivery_event` | provider_id, provider_event_id unique | Callback dedupe |
| `idempotency_record` | tenant_id, idem_key unique, request_hash | Redis is a fast path, the DB is the source of truth |

Mongo `provider_diagnostics` holds raw callback payloads, best-effort only. Redis holds idempotency cache and token buckets. Payload stores variables, not the rendered body.

### 1.5 Flow

```
 Client
   |  POST /messages (Idempotency-Key)
   v
 API (NotificationService)
   |  1 validate, 2 idempotency claim, 3 render-check template
   |  4 ONE TX: message_request(VALIDATED) + outbox_event + audit
   v  202 Accepted
 Postgres ---- OutboxPublisher (200 ms poll, CAS claim) ----> RabbitMQ
                                                    priority queues, x-max-priority=9
                                                              |
                                                              v
                                                       DispatchConsumer
   dedupe (processed_message) -> CAS QUEUED->PROCESSING -> policy recheck
   -> render (pinned version) -> token bucket (Redis) -> ResilientProviderInvoker
                                                              |
                  +-------------+-----------------+-----------+
                  v             v                 v
              ACCEPTED   TRANSIENT/RATE_LIMITED  PERMANENT
         SENT_TO_PROVIDER  RETRY_PENDING        FAILED (+fallback)
                  |             |
          callback DELIVERED   backoff, requeue; exhausted -> DLQ
                  v
              DELIVERED
```

Steps:
1. The submission transaction commits the message and outbox row together. This removes the dual-write problem: either both exist or neither does.
2. `OutboxPublisher` claims batches with a compare-and-set update and publishes to the bus, moving the status to QUEUED.
3. The consumer deduplicates on `dispatch_id`, wins the QUEUED to PROCESSING race with a CAS, and re-evaluates policy (cancel, expiry, suppression, quiet hours).
4. It checks the provider token bucket. If empty, it requeues with a short delay without consuming an attempt.
5. It calls the provider through timeout plus circuit breaker, records the attempt, and maps the outcome to the next state.
6. Callbacks move SENT_TO_PROVIDER to DELIVERED or FAILED, using the rank rule (CONTRACT section 3.4).

### 1.6 Failure modes

| Failure | Detection | Recovery |
|---|---|---|
| App crashes after commit, before publish | Outbox row stays NEW | Publisher picks it up after restart. At-least-once |
| Publisher publishes, crashes before marking SENT | Duplicate publish possible | Consumer dedupe via `processed_message` and CAS |
| Provider call succeeds, crash before saving result | PROCESSING with stale `locked_until` | `StuckMessageSweeper` sets DELIVERY_UNKNOWN, then reconciliation. No blind retry |
| Provider slow or down | Circuit state gauge, timeout outcomes | Circuit opens, messages go RETRY_PENDING, fallback provider or channel if allowed |
| Poison message | Repeated exception at or above `poisonThreshold` | DLQ with POISON_MESSAGE. Never an infinite loop |
| Redis down | `messaging.redis.fallback` increments | `ResilientCoordination` falls back to DB or in-memory limits. Limits get looser, correctness holds |
| Mongo down | `messaging.diagnostics.write.failures` | Ignored for processing, best-effort |
| Queue backlog | `messaging.queue.depth` over threshold | API backpressure returns 503 with `Retry-After` |
| Duplicate client retry | Same Idempotency-Key | Same response, no second message |

### 1.7 Trade-offs

| Decision | Chosen | Alternative | Cost of choice |
|---|---|---|---|
| Bus | RabbitMQ (ADR-0001): priorities, per-message TTL, DLX, simple ops | Kafka: replay, stream analytics, huge fan-out | No native replay or long retention. Priority fairness needs care |
| Store | PostgreSQL (ADR-0005) | Oracle or MongoDB as system of record | Single-writer scaling limits, hence partitioning and replicas |
| Publish | Outbox with poller | CDC (change data capture) | Poll latency (200 ms), extra DB load. Simpler to run |
| Delivery | At-least-once plus idempotent consumer | Exactly-once claim | Must handle duplicates. Honest across the provider boundary |
| Ambiguous outcome | Wait and reconcile for EMAIL/SMS | Retry immediately | Slower recovery, but no double-send |

### 1.8 What to measure

Counters and timers by name (CONTRACT section 8): `messaging.messages.accepted`, `messaging.dispatch.delay` (SLI for accept to provider submit), `messaging.provider.calls{outcome}`, `messaging.provider.latency`, `messaging.provider.circuit.state`, `messaging.queue.depth{priority}`, `messaging.queue.dlq.depth`, `messaging.retry.scheduled`, `messaging.deadletter.total{reason}`. Plus `http_server_requests_seconds` and `hikaricp_connections_*`.

Example SLOs: 99.9 percent of CRITICAL messages reach SENT_TO_PROVIDER within 5 s. DLQ depth zero for more than 15 minutes pages someone. Alert on oldest-message age, not just depth.

### 1.9 Follow-up questions

1. How do you prioritise a password-reset SMS over a marketing email during a backlog? (priority queues, separate queues per class, reserved rate-limit tokens)
2. How would you add a fourth provider? (provider port plus registry plus config. The `AcmeSmsProvider` worked example)
3. What changes at 10x? (shard by tenant or recipient hash, move to Kafka for replay and fan-out, CDC instead of polling)
4. How do you avoid hot partitions for a single huge tenant? (per-tenant quotas, weighted fair queuing)
5. How do you guarantee no PII in logs? (envelope has no content, `Redaction` helper, masked addresses in API)

---

## Walkthrough 2: Delivery-status reconciliation

**Analogy:** a package says "handed to courier" but the delivery photo never arrives. A clerk phones the courier with the tracking number and asks "did this arrive?". The clerk never ships a second copy until the courier says it was not received.

### 2.1 Requirements

- Provider acceptance does not imply delivery (CONTRACT section 3.1). The system must converge each message to a terminal truth: DELIVERED or FAILED.
- Handle duplicate, late, out-of-order, and missing callbacks.
- Resolve DELIVERY_UNKNOWN without causing duplicate sends.
- Keep an audit trail of every applied or ignored event.

### 2.2 Capacity math

| Quantity | Calculation | Result |
|---|---|---|
| Messages reaching SENT_TO_PROVIDER per day | 30,000,000 (all accepted) | 30,000,000 |
| Callback events per message | about 2 (SENT, DELIVERED) | 60,000,000 per day |
| Average callback rate | 60,000,000 / 86,400 | about 694 per second |
| Peak callback rate (10x) | 694 x 10 | about 7,000 per second |
| Dedup table growth | 60,000,000 x 100 B | about 6 GB per day |
| Receipts never arriving (assume 0.1 percent) | 30,000,000 x 0.001 | 30,000 per day need lookup |
| Provider lookup budget (assume 20 req/s) | 30,000 / 20 | 1,500 s, about 25 minutes of lookups per day |
| Sweeper scan (every 60 s) | index on (status, updated_at) | cheap range scan |

Takeaway: callback ingestion is write-heavy but each write is tiny. Lookups are rare, so reconciliation is rate-limited by the provider, not by us.

### 2.3 API

```
POST /api/v1/callbacks/{providerId}
  X-Signature: t=<epoch>,v1=<hex>     HMAC-SHA256 over t + "." + rawBody
  X-Provider-Key-Id, X-Event-Id
GET  /api/v1/failed-messages            FAILED, DELIVERY_UNKNOWN, RETRY_PENDING
GET  /api/v1/delivery-events            search: messageId, type, applied
GET  /api/v1/messages/{id}/history      merged timeline
```

The callback endpoint is `permitAll` at the Spring Security layer but verified by `SignatureVerifier` (5-minute tolerance, `ReplayGuard` on `X-Event-Id`). Never trust the network.

### 2.4 Data model

| Table | Purpose |
|---|---|
| `delivery_event` | One row per provider event. Unique (provider_id, provider_event_id). Columns applied, ignored_reason (OUT_OF_ORDER, STALE, DUPLICATE) |
| `message_request` | Current status and timestamp. Monotonic rank rule decides if an event may change it |
| `message_attempt` | Provider message id and idempotency reference for lookup |
| `replay_guard` | event_id with seen_at, purged after tolerance window |
| Mongo `provider_diagnostics` | Raw payload archive, best effort |

Rank rule (CONTRACT section 3.4): PROVIDER_ACCEPTED, SENT, DEFERRED = 1. DELIVERED, BOUNCED, FAILED, REJECTED = 3. A lower rank never overwrites a higher one. A DELIVERED then BOUNCED conflict keeps the first terminal event and audits `DELIVERY_EVENT_CONFLICT`.

### 2.5 Flow

```
 Provider --webhook--> CallbackController
                          | verify signature + tolerance (SignatureVerifier)
                          | replay check (ReplayGuard)
                          v
                    EventNormalizer (per-provider mapper)
                          v
                    DeliveryEventProcessor
          insert delivery_event (unique key => duplicate ignored)
          rank check + occurred_at check => applied true/false
          CAS update message status, audit
                          v
                  message DELIVERED / FAILED

 StuckMessageSweeper (periodic)
   SENT_TO_PROVIDER older than receipt-timeout  --> DELIVERY_UNKNOWN
   PROCESSING with stale locked_until            --> DELIVERY_UNKNOWN

 ReconciliationService (after reconcile-after)
   lookup(idempotencyRef) at provider
     DELIVERED      --> DELIVERED
     FAILED         --> FAILED
     NOT_RECEIVED   --> safe retry (QUEUED via operator/retry path)
     still unknown  --> stay, alert, widen interval
```

### 2.6 Failure modes

| Failure | Handling |
|---|---|
| Duplicate callback | Unique key rejects it. Counter `messaging.callbacks{result=duplicate}`. Respond success so the provider stops retrying |
| Out-of-order (DELIVERED before SENT) | DELIVERED applied. Later SENT recorded, ignored with OUT_OF_ORDER |
| Forged callback | Invalid signature, counter `invalid_signature`, no state change |
| Replayed old callback | Outside tolerance or seen event id, counter `replayed` |
| Callback for unknown message | Store event unapplied, reconcile later. Do not 5xx forever (provider retry storm) |
| Provider has no lookup API | Keep DELIVERY_UNKNOWN, surface in failed-messages list, operator decision |
| Conflicting terminals | Keep the first, audit conflict, alert on rate |
| Callback burst | Ingest asynchronously, bounded executor, backpressure with 503 and `Retry-After` |

### 2.7 Trade-offs

| Decision | Chosen | Alternative | Cost |
|---|---|---|---|
| Ambiguous send | Wait, then look up | Retry now | Slower to recover, but avoids duplicate SMS and email |
| Event handling | Record everything, apply by rank | Apply last-write-wins | More storage, correct under reordering |
| Webhook auth | HMAC plus timestamp plus replay cache | IP allow-list only | Needs secret rotation via `X-Provider-Key-Id` |
| Push on ambiguity | Retry allowed (collapse key makes it idempotent) | Same as SMS | Different policy per channel to explain |

### 2.8 What to measure

`messaging.callbacks{result}` (accepted, duplicate, invalid_signature, replayed, out_of_order, stale), count of messages in DELIVERY_UNKNOWN and its age, `messaging.transitions{from,to}` for SENT_TO_PROVIDER to DELIVERED conversion rate, time-to-terminal histogram. Alert when DELIVERY_UNKNOWN count grows for more than one receipt-timeout, or `invalid_signature` spikes (possible attack or key rotation error).

### 2.9 Follow-up questions

1. Why not mark SENT_TO_PROVIDER as delivered? (acceptance is not delivery, customers and audits care)
2. How do you make the lookup safe? (idempotency reference `messageId:attemptGroup`, provider dedupes)
3. What if the provider clock is skewed? (tolerance window, key id rotation, time sync alert)
4. How would you reconcile against a provider's daily report file? (batch job diff by provider message id, report mismatches)
5. How do you test out-of-order handling? (simulator callback emit with `duplicate` and `outOfOrder` options, per CONTRACT section 5)

---

## Walkthrough 3: Bulk campaign sends with rate limits

**Analogy:** a stadium with one gate. Ten thousand fans arrive at once, but the gate lets in a fixed number per second. A VIP lane stays open so urgent guests are never stuck behind the crowd.

### 3.1 Requirements

- Upload or generate a recipient file of up to millions of rows, send a templated campaign.
- Never load the whole file in memory. Restartable after failure.
- Respect per-provider rate limits and not starve transactional traffic.
- Report progress, failures per row, duplicates.
- Consent and quiet hours still apply (marketing is deferred or suppressed).

### 3.2 Capacity math

| Quantity | Calculation | Result |
|---|---|---|
| Campaign size | given | 5,000,000 recipients |
| Provider capacity | two SMS providers at 300 and 200 per second | 500 per second |
| Reserved for transactional (20 percent) | 500 x 0.2 | 100 per second held back |
| Campaign budget | 500 - 100 | 400 per second |
| Campaign duration | 5,000,000 / 400 | 12,500 s, about 3.5 hours |
| Without reservation | 5,000,000 / 500 | 10,000 s, about 2.8 hours |
| CSV size | 5,000,000 x 100 B | about 500 MB, streamed |
| Chunks (size 500) | 5,000,000 / 500 | 10,000 chunks |
| Write time at 100 ms per chunk, single thread | 10,000 x 0.1 s | 1,000 s, about 17 minutes |
| With `bulk.concurrency` 4 | 1,000 / 4 | about 250 s, so the DB insert is not the bottleneck, the provider limit is |
| Queue depth if enqueued at once | up to 5,000,000 x 300 B | about 1.5 GB, too large, so the writer throttles at `rate_limit_per_sec` instead of dumping everything |

### 3.3 API

```
POST /api/v1/bulk-jobs                 multipart CSV + name, templateCode, channel, category, ratePerSecond  -> 202
GET  /api/v1/bulk-jobs/{id}            progress counters
GET  /api/v1/bulk-jobs/{id}/failures   paged row failures
POST /api/v1/bulk-jobs/{id}/stop | /restart
```

Needs role SENDER and scope `messages:bulk` (CONTRACT section 5). Maximum rows from config `messaging.bulk.max-rows`.

### 3.4 Data model

| Table | Notes |
|---|---|
| `bulk_job` | status (CREATED, RUNNING, COMPLETED, COMPLETED_WITH_ERRORS, FAILED, STOPPED, ABANDONED), total, processed, succeeded, failed, duplicate, rate_limit_per_sec, spring_batch_execution_id, version |
| `bulk_job_failure` | job_id, line_no, reason, raw_line_hash (hash, not the raw line, to avoid PII) |
| `message_request` | bulk_job_id set. Idempotency key `bulk-<jobId>-<externalRef>` makes restart safe |
| Spring Batch tables | JDBC job repository in the same DB (`V100__spring_batch.sql`) |

### 3.5 Flow

```
 CSV (streamed) --> FlatFileItemReader --> RecipientRowProcessor --> BulkMessageWriter
                    chunk(500)             validate columns          one TX per chunk
                                           resolve recipient         submitInternal(...)
                                           dedupe (job, externalRef) idempotency key per row
                                                                     writer-side RateLimiter
                                                                          |
                                                              VALIDATED message_request + outbox
                                                                          v
                                                    OutboxPublisher -> RabbitMQ (LOW/NORMAL priority)
                                                                          v
                                           DispatchConsumer -> provider token bucket (shared with API traffic)
```

Skip policy: skip up to `skipLimit` bad rows into `bulk_job_failure`. Retry transient DB errors 3 times. Restart relaunches the same JobParameters and resumes from the last committed chunk. Multi-threaded step only behind `SynchronizedItemStreamReader`.

### 3.6 Failure modes

| Failure | Handling |
|---|---|
| App dies mid-job | Restart from last committed chunk. Idempotency key prevents duplicate rows |
| Bad rows (invalid phone, missing column) | Skipped, counted, listed in failures. Job ends COMPLETED_WITH_ERRORS |
| Too many bad rows | Skip limit exceeded, job FAILED, no more sends |
| Provider rate limit hit | Token bucket empty, requeue with short delay, no attempt consumed |
| Provider outage mid-campaign | Circuit opens, messages wait in RETRY_PENDING. Operator can STOP the job |
| Campaign floods the queue | Writer throttle plus lower priority. Transactional uses higher priority and reserved tokens |
| Duplicate recipients in file | In-chunk set plus unique (job, externalRef). Counted as `duplicate` |
| Wrong template or file uploaded | STOP, cancel VALIDATED and QUEUED messages (cancellation allowed in those states) |
| Quiet hours | Marketing deferred by moving `scheduled_at` to quiet-hours end, status stays VALIDATED |

### 3.7 Trade-offs

| Decision | Chosen | Alternative | Cost |
|---|---|---|---|
| Engine | Spring Batch chunking | Custom loop or streaming service | Framework weight, but restart and metadata are built in |
| Rate control | Writer-side limiter plus consumer-side token bucket | Only consumer-side | Two controls to tune, but the queue stays small |
| Chunk size | 500 | 5,000 | Bigger chunks mean fewer commits, but longer locks and more rework on failure |
| Priority | Reserve 20 percent of tokens for transactional | Strict priority only | Campaign runs about 25 percent longer (12,500 s vs 10,000 s) |
| Fan-out to bus | Throttled | Enqueue all | Needs job state to be the source of truth for what remains |

### 3.8 What to measure

`messaging.bulk.progress{jobId}`, bulk job counters (processed, failed, duplicate), `messaging.ratelimit.rejected{scope}`, `messaging.provider.calls{outcome}` by provider, `messaging.queue.depth{priority}`, `messaging.dispatch.delay` split by priority (to prove campaigns do not hurt transactional latency), DB pool usage `hikaricp_connections_active`.

### 3.9 Follow-up questions

1. How do you stop a campaign that is already half sent? (stop job, cancel VALIDATED/QUEUED/RETRY_PENDING messages, already SENT_TO_PROVIDER cannot be recalled)
2. How do you A/B split recipients deterministically? (hash of externalRef modulo N, recorded in metadata)
3. What if two providers have different limits? (per-provider token buckets, weighted routing by `priority_order`)
4. How do you guarantee a transactional message is not blocked by the campaign? (separate priority, reserved tokens, per-category queues if needed)
5. How do you partition the step for more speed? (partitioned step by row range, each partition with its own reader, instead of sharing one reader)

---

## Walkthrough 4: Multi-region disaster recovery

**Analogy:** a school keeps a spare copy of the attendance book in another building. If the first building closes, teachers walk to the second. The question is how many pages the spare copy is behind, and how fast the teachers can start using it.

### 4.1 Requirements

- Survive loss of one region.
- RTO (recovery time objective) 15 minutes. RPO (recovery point objective) 5 seconds for accepted messages.
- No message loss after a 202 where practical, and clearly defined behaviour when the RPO gap loses some.
- Avoid double-sending after failover.
- Regulated data: residency rules, audited failover.

### 4.2 Capacity math

| Quantity | Calculation | Result |
|---|---|---|
| Peak accept rate | from walkthrough 1 | 3,500 per second |
| Replication lag assumption (async) | given | up to 5 s |
| Messages at risk in the RPO window at peak | 3,500 x 5 | 17,500 messages |
| Writes at risk (about 9 per message) | 17,500 x 9 | about 157,500 row changes |
| WAL (write-ahead log) volume at 2 KB per message | 3,500 x 2 KB | about 7 MB/s to replicate, 70 Mbit/s with overhead, so plan inter-region bandwidth |
| Failover budget | RTO 900 s | detection 60 s, decision 120 s, promote DB 120 s, scale pods 300 s, DNS and caches 300 s = 900 s |
| Backlog to drain after failover | outage 15 min x 347 per second average (clients retry) | about 312,000 messages |
| Drain time at 3,500 per second | 312,300 / 3,500 | about 90 s extra, provided providers allow the rate |

### 4.3 Topology options

| Option | RPO | RTO | Cost | Notes |
|---|---|---|---|---|
| Backup and restore | hours | hours | low | Not acceptable here |
| Active-passive (warm standby), async replica | seconds | minutes | medium | Chosen. Simple, one writer |
| Active-passive with synchronous replica | zero | minutes | medium | Commit latency includes cross-region round trip, availability tied to the standby |
| Active-active | near zero | seconds | high | Needs conflict resolution, partition tenants by home region |

Recommended path: active-passive per tenant home region, with the option to evolve to active-active by tenant sharding (each tenant has exactly one writer region).

### 4.4 Data and component placement

| Component | Primary region | Standby region |
|---|---|---|
| PostgreSQL | Writer | Streaming async replica, promoted on failover |
| RabbitMQ | Cluster | Standby cluster. Queues are not mirrored across regions. Messages are rebuilt from the DB (outbox plus status) |
| Redis | Cache and token buckets | Cold or warm. Treated as rebuildable. Limits start loose after failover |
| MongoDB | Diagnostics | Replicated or accepted as lossy (best-effort data) |
| Secrets, config | Same | Same, region-scoped keys |

Key design point: **the database is the source of truth, the bus is rebuildable**. After failover, a recovery job re-publishes messages that are QUEUED or VALIDATED with a pending outbox row. Consumer dedupe (`processed_message`, CAS transitions) makes re-publish safe.

### 4.5 Flow

```
 Normal:   Clients -> Global LB -> Region A (API, consumers, Postgres primary)
                                         |  WAL stream (async)
                                         v
                                   Region B (Postgres replica, standby app at low replica count)

 Failover:
   1 detect (health checks fail, multi-signal)      60 s
   2 decide (human or automated, runbook)           120 s
   3 fence region A (stop writers, revoke leases)   -- prevents split brain
   4 promote Postgres replica in B                  120 s
   5 scale app and consumers in B                   300 s
   6 reconcile: re-publish QUEUED/outbox NEW, sweep PROCESSING -> DELIVERY_UNKNOWN
   7 flip DNS or global LB, clients retry with the same Idempotency-Key
   8 verify metrics, announce, later fail back deliberately
```

### 4.6 Failure modes

| Failure | Handling |
|---|---|
| Split brain (both regions write) | Fencing: stop old primary before promotion, single-writer rule per tenant |
| Accepted but not replicated (RPO gap) | Client got 202 but the row is gone in B. Client retries with the same Idempotency-Key and gets a fresh accept. If the client does not retry, the message is lost. Publish the RPO to clients and offer sync replication for CRITICAL tenants |
| Sent to provider but status not replicated | Message looks QUEUED in B and would be sent again. Mitigation: provider idempotency reference (`messageId:attemptGroup`) so a compliant provider dedupes. Residual duplicate risk is documented, not hidden |
| Provider callbacks arrive at a dead region | Providers retry. Configure callback URL on the global endpoint |
| Redis lost | Rate limits reset. Start conservative, provider limits protect us |
| Stale DNS or client caches | Low TTL, global load balancer health checks |
| Failover itself fails | Game days, runbooks, rehearsed failover in a non-prod environment |
| Failback creates a second outage | Treat failback as a planned migration, not an emergency |

### 4.7 Trade-offs

| Decision | Chosen | Alternative | Cost |
|---|---|---|---|
| Replication | Async | Sync | Possible loss of up to 5 s of data, in return for write latency and availability |
| Topology | Active-passive | Active-active | Standby capacity idle, but no conflict resolution |
| Bus replication | Rebuild from DB | Federate or shovel queues | Recovery job needed, but fewer moving parts |
| Duplicate risk | Accept small, document, mitigate with idempotency ref | Block sends until certain | Delay of customer-critical messages is worse for security alerts |
| Failover trigger | Human-approved | Fully automatic | Slower, but avoids flapping and split brain |

### 4.8 What to measure

Replication lag (alert at 2 s, page at 5 s), time since last successful backup and last restore test, failover drill duration against RTO, outbox NEW age after failover, `messaging.queue.depth` and `messaging.dispatch.delay` during recovery, `messaging.callbacks{result=duplicate}` spike after failback, count of messages marked DELIVERY_UNKNOWN by the sweeper after promotion.

### 4.9 Follow-up questions

1. Why not mirror RabbitMQ queues across regions? (latency, partition risk, DB already holds the truth)
2. How do you test DR without taking prod down? (game day in staging, replica promotion drill, chaos on a single tenant)
3. How do you handle data residency? (tenant home region, region-scoped keys, no cross-border replication for restricted tenants)
4. What changes for active-active? (tenant sharding with one writer per tenant, global idempotency keyspace per tenant, conflict-free ids)
5. How do you prove RPO and RTO to an auditor? (drill records, replication lag history, runbook evidence. Nothing is claimed untested)
