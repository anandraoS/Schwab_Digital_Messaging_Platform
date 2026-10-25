# 13. Retry and Dead-Letter Flows

## 13.1 Principles

Retries are the most dangerous reliability feature: used carelessly they turn a small provider hiccup into a self-inflicted outage (a retry storm) or into duplicate messages. The platform's retry design follows five principles (ADR-0006):

1. Retry only what is **safe**: transient failures and rate limiting. Uncertain outcomes go to reconciliation (Chapter 12), never to a blind resend.
2. Spread retries in time: **exponential backoff with jitter** and a cap.
3. Bound everything: a maximum number of attempts per message, a poison threshold per envelope.
4. Park, do not discard: exhausted messages go to a **dead-letter queue** with a reason, visible to operators.
5. Recovery is deliberate: an **operator** replays, with an audit record and a fresh attempt budget.

A kid-friendly analogy: if the doorbell is not answered, you do not ring it 50 times in a row. You wait a little, ring again, wait longer, and after the fifth try you leave a note with the neighbour (the dead-letter queue) so a grown-up can decide what to do.

## 13.2 What is retried

`ProviderOutcome.isRetryable()` returns true only for `TRANSIENT_FAILURE` and `RATE_LIMITED`. `RetryPolicy.isRetryable(outcome)` is the single place the consumer asks.

| Outcome | Automatic retry | Reason |
|---|---|---|
| `TRANSIENT_FAILURE` | Yes, with backoff | Provider 5xx or network error |
| `RATE_LIMITED` | Yes, with backoff | Provider 429 |
| `PERMANENT_FAILURE` | No (fallback channel if allowed) | Invalid recipient, rejection |
| `TIMEOUT`, `AMBIGUOUS` | No for EMAIL and SMS (reconcile); yes for PUSH | The provider may already have it |
| Rate limiter refusal (our token bucket) or open circuit | Requeue 0.1 to 5 s, **no attempt consumed** | Not the message's fault |

## 13.3 Backoff with jitter

```java
// com.example.messaging.service.RetryPolicy
public Duration ceiling(int attemptNo) {
    int shift = Math.min(Math.max(attemptNo - 1, 0), 30);
    long raw = baseMs * (1L << shift);
    if (raw < 0 || raw > capMs) raw = capMs;
    return Duration.ofMillis(raw);
}
/** Jittered backoff in [ceiling/2, ceiling]. */
public Duration backoff(int attemptNo, RandomGenerator random) {
    long ceil = ceiling(attemptNo).toMillis();
    double factor = 0.5 + 0.5 * random.nextDouble();
    return Duration.ofMillis(Math.max(1, (long) (ceil * factor)));
}
```

The formula is `next_attempt_at = now + min(cap, base x 2^(n-1)) x U(0.5, 1.0)`. With the shipped defaults (`backoff-base-ms` 1000, `backoff-cap-ms` 60000, `max-attempts` 5) the ceilings are 1, 2, 4, 8, 16 seconds for the first five retries, then 32 and 60 for configurations with more attempts.

{{diagram:backoff}}

Jitter matters as much as the exponent: without it, thousands of messages that failed together retry together and re-create the overload. `RetryPolicy` takes a `RandomGenerator` so tests are deterministic (`RetryPolicyTest`).

## 13.4 The retry loop

{{diagram:dlqFlow}}

1. The consumer maps a retryable outcome to `RETRY_PENDING`, sets `next_attempt_at`, increments `messaging.retry.scheduled{channel}`, and republishes the envelope with `publishDelayed` and a **new dispatchId** (`messageId:attemptNo`).
2. Delayed redelivery is done by the bus (Rabbit TTL tiers 5 s, 30 s, 2 m, 10 m; in-memory timers). `ScheduledDispatchPoller` (every second) is the safety net: it re-enqueues `RETRY_PENDING` messages whose backoff elapsed by more than `retry-grace` (5 s) but whose delayed envelope never arrived, repairs due `VALIDATED` messages that lack an outbox row, and re-publishes `QUEUED` messages whose envelope was lost (for example after an in-memory bus restart). A duplicate envelope is harmless because the consumer dedupes.
3. Both paths end in the same compare-and-set `RETRY_PENDING -> QUEUED`, so a retry can never run twice.
4. When attempts are exhausted: a **fallback channel** if the request allowed one and policy approves (`PROCESSING -> RETRY_PENDING -> QUEUED` with a fresh budget), otherwise `DEAD_LETTERED(RETRIES_EXHAUSTED)`.

## 13.5 Dead-letter queue

Dead letters have **two representations** that are kept consistent: the bus DLQ (for poison envelopes) and the database status `DEAD_LETTERED` with a `dlq_reason` (the authoritative one, queryable through the API).

| Reason (`DlqReason`) | Cause | Counter |
|---|---|---|
| `RETRIES_EXHAUSTED` | Attempts used up without success or fallback | `messaging.deadletter.total{reason}` |
| `POISON_MESSAGE` | Envelope references a missing message, cannot be deserialised, or keeps throwing on `poison-threshold` (5) deliveries | same |
| `PERMANENT_FAILURE`, `OPERATOR` | Defined in the `DlqReason` enum for permanent-failure parking and operator action; the automatic pipeline itself produces the two reasons above | same |

`DispatchHandler.onPoison(envelope, deliveryCount, cause)` lets the bus tell the consumer when it dead-lettered an envelope on its own, so the consumer mirrors that into the database with legal transitions (`QUEUED -> PROCESSING -> FAILED -> DEAD_LETTERED`).

### Operator tools

| Endpoint | Roles | Effect |
|---|---|---|
| `GET /api/v1/dead-letters` | OPERATOR, TENANT_ADMIN, PLATFORM_ADMIN | Paged list with reason |
| `POST /api/v1/dead-letters/{messageId}/replay` | OPERATOR, PLATFORM_ADMIN | Policy pre-check (audited), CAS `DEAD_LETTERED -> QUEUED`, new outbox event |
| `POST /api/v1/dead-letters/{messageId}/discard` | OPERATOR, PLATFORM_ADMIN | Audited; stays `DEAD_LETTERED` with a `discarded` flag |
| `POST /api/v1/messages/{id}/requeue` | OPERATOR, PLATFORM_ADMIN | `FAILED` or `DEAD_LETTERED` to `QUEUED` |
| `GET /api/v1/failed-messages` | OPERATOR, TENANT_ADMIN, PLATFORM_ADMIN | `FAILED`, `DELIVERY_UNKNOWN`, `RETRY_PENDING` investigation list |

Replay semantics worth remembering (DEVIATIONS stage 2): `NotificationService.requeueInternal` sets `max_attempts = attempt_count + messaging.dispatch.max-attempts`, so a replayed message gets a **fresh budget** instead of failing on its first retry; the outbox publisher must publish regardless of whether the message is already `QUEUED`. The console warns before replaying a message in `DELIVERY_UNKNOWN`, because it may already have been delivered.

## 13.6 Retry storms

A retry storm is retries amplifying an incident. The defences stack:

| Defence | Effect |
|---|---|
| Full jitter and cap | Retries spread in time, never faster than the cap allows |
| Max attempts | Bounded amplification per message (at most 5 by default) |
| Shared token bucket per provider | Retries and first attempts share one rate limit |
| Circuit breaker | During an outage, calls stop entirely and requeues consume no attempt |
| Alert `RetryStorm` | Retries above half the accept rate pages the on-call (runbook `retry-storm.md`) |
| `retry-storm.js` k6 script | Designed to prove boundedness (NOT executed here) |

## 13.7 Tests that prove it

| Test | Proves |
|---|---|
| `RetryPolicyTest` | Ceiling, jitter bounds, overflow safety, retry classification |
| `FailureRecoveryIT#scenario01_*` | Provider outage, backoff windows, DLQ after N attempts, replay reaches `SENT_TO_PROVIDER` then `DELIVERED` |
| `FailureRecoveryIT#scenario08_*` | Poison messages dead-lettered with no infinite loop |
| `ReplayServiceIT` | Replay and requeue semantics, fresh budget, audit |
| `InMemoryMessageBusTest` | Delayed redelivery and DLQ behaviour |
| `tests/failure-recovery/provider-outage.mjs` | Scripted outage and recovery against a started `local` application |

## Reference material

{{embed:docs/adr/0006-retry-and-dead-letter-policy.md|title=ADR-0006: Retry and dead-letter policy}}

{{embed:docs/operations/runbooks/dlq-growth.md|title=Runbook: DLQ growth}}

{{embed:docs/operations/runbooks/retry-storm.md|title=Runbook: Retry storm}}
