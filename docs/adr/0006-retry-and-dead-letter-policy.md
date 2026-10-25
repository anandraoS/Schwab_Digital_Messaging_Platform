# ADR-0006: Retry and dead-letter policy

Status: Accepted (2026-10-18)

## Context
Provider failures are common and bursty. Naive immediate retry amplifies outages (retry storm), synchronized retries create thundering herds, and endless retry of an unprocessable message blocks capacity.

## Decision
### Classification drives behavior (`RetryPolicy`)
| `ProviderOutcome` | Action |
|---|---|
| ACCEPTED | `SENT_TO_PROVIDER` |
| TRANSIENT_FAILURE, RATE_LIMITED | `RETRY_PENDING` with backoff; consumes an attempt, except provider-limiter exhaustion (our own token bucket empty), which requeues with short delay and consumes **no** attempt |
| PERMANENT_FAILURE | `FAILED`; fallback channel if allowed; hard bounce adds `suppression` |
| TIMEOUT, AMBIGUOUS | `DELIVERY_UNKNOWN`, no blind retry for EMAIL/SMS (ADR-0010) |

### Backoff: exponential with full jitter
`delay = min(cap, base * 2^(n-1))`, then `next_attempt_at = now + delay * U(0.5, 1.0)` (the contract's "full-jitter variant" keeps at least half the exponential delay so retries do not collapse to ~0). Config: `messaging.dispatch.backoff-base-ms`, `backoff-cap-ms`, `max-attempts`. Illustrative defaults: base 2 s, cap 5 min, 5 attempts. A provider `Retry-After` hint, if present, raises the delay to at least that value (never lowers).

Worked illustration (base 2 s, cap 300 s): n=1 → 2 s window (1-2 s); n=2 → 4 s (2-4 s); n=3 → 8 s (4-8 s); n=4 → 16 s (8-16 s); n=5 → 32 s (16-32 s).

### Caps and bounds
- Max attempts per message and per channel group; total retry horizon also bounded by `expires_at` (expired ⇒ not sent, status `FAILED` with reason `EXPIRED`, see source).
- Retry never bypasses consent: before each attempt `PolicyService` is re-evaluated (cancel, expiry, suppression, quiet hours).
- Circuit breaker open ⇒ requeue with delay without consuming an attempt; breaker state exposed via `messaging.provider.circuit.state{provider}`.
- Global retry-storm guard: retries share the provider token bucket with first attempts; first attempts of higher priority are favoured by queue priority.

### Dead letter
- `DlqReason{RETRIES_EXHAUSTED, POISON_MESSAGE, PERMANENT_FAILURE, OPERATOR}`; message status `DEAD_LETTERED`; broker DLQ holds the envelope; DB row holds the reason. Counter `messaging.deadletter.total{reason}`, gauge `messaging.queue.dlq.depth`.
- **Poison**: missing message, deserialization failure, or `poison-threshold` consecutive unexpected exceptions for the same `dispatchId` ⇒ DLQ with `POISON_MESSAGE` immediately; never infinite redelivery.
- **Replay**: only operators (`POST /api/v1/dead-letters/{messageId}/replay`, `POST /api/v1/messages/{id}/requeue`) via `ReplayService`; creates a new `dispatchId`/attempt group, re-runs policy, audits actor and reason. `discard` keeps status but flags the record. Replay of a message whose final provider outcome was ambiguous requires the reconciliation check first (see ADR-0010).

## Alternatives considered
| Option | Why not |
|---|---|
| Fixed-interval retry | Synchronizes load; no relief for providers |
| Unbounded retries | Poison loop, capacity loss |
| Broker-only retry (requeue on nack) | No attempt accounting, no per-class policy, immediate redelivery storms |
| Retry inside the provider adapter | Hidden attempts; breaks accounting and ADR-0002 |
| Equal jitter vs decorrelated jitter | Decorrelated is fine; chosen variant is easier to test deterministically with an injected `Clock` and `Random` |

## Consequences
- Positive: bounded work, observable, tunable, operator control.
- Negative: worst-case latency for retried messages is minutes; critical alerts may use fallback channel sooner (policy step 5).
- Risk: a long provider outage fills `RETRY_PENDING` and then the DLQ; see runbooks `provider-outage.md`, `retry-storm.md`, `dlq-growth.md`.

## Validation
- `RetryPolicyTest` (deterministic `Random`) asserts bounds per attempt; `FailureRecoveryIT#scenario01_providerOutage_retriesWithBackoff_thenDeadLetters_thenReplay`, `#scenario08_poisonMessage_goesToDlq_noInfiniteLoop`.
