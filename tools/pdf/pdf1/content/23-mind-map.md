# 23. Architecture Mind Map

## 23.1 The whole platform on one page

The mind map is a memory aid: nine branches, each a concern, each with the mechanisms that answer it. If you can walk the map from memory and explain each leaf in one sentence, you can explain the platform.

{{diagram:mindmap}}

## 23.2 From concern to mechanism to evidence

| Concern | Mechanism | Where it lives | ADR | Chapter | Proof |
|---|---|---|---|---|---|
| Never lose an accepted message | Transactional outbox, DB as source of truth | `scheduler/OutboxPublisher`, `messaging/OutboxWriter` | 0009 | 7 | Scenarios 1 and 6 |
| No duplicates | Four-layer idempotency | `IdempotencyService`, `DispatchConsumer`, `ReplayGuard`, `ProviderRequest` | 0004 | 5 | Scenarios 2, 3 |
| Honest status | 12-state machine, CAS | `domain/MessageStateMachine` | 0003 | 6 | `MessageStateMachineTest` |
| Do not guess | `DELIVERY_UNKNOWN`, reconciliation | `StuckMessageSweeper`, `ReconciliationService` | 0010 | 12 | Scenarios 4, 5 |
| Survive provider trouble | Breaker, backoff with jitter, DLQ, replay | `ResilientProviderInvoker`, `RetryPolicy` | 0006 | 8, 13 | Scenarios 1, 8, 10 |
| Swap vendors | Ports and adapters | `ChannelProvider`, `ProviderRegistry` | 0002 | 8 | `NewProviderWithoutOrchestrationChangeTest` |
| Respect the customer | Ordered policy, twice | `PolicyService` | 0012 | 11 | `PolicyServiceTest` |
| Govern content | Versioned, four-eyes, pinned templates | `TemplateService`, `TemplateRenderer` | 0007 | 10 | `TemplateLifecycleIT` |
| Trust no one | JWT, tenant 404, HMAC callbacks | `SecurityConfig`, `SignatureVerifier` | 0011 | 16 | `SecurityIT`, `TenantIsolationIT` |
| Scale sends | Spring Batch chunks, token buckets | `batch/*` | | 14 | Scenario 9 |
| Operate it | Metrics, alerts, runbooks | `MessagingMetrics`, `observability/` | | 20 | Static validation only |
| Run it anywhere | Profiles, CF and K8s assets | `cloud-foundry/`, `infrastructure/` | 0008 | 17, 18 | Parsed only, NOT executed |

## 23.3 One message, end to end (the narrative to remember)

1. A **sender** posts with an `Idempotency-Key` and gets **202** after one transaction wrote the message, outbox row, audit event and idempotency response.
2. The **outbox publisher** claims the row, moves the message to `QUEUED`, and publishes an **envelope** (ids only).
3. A **consumer worker** checks cancel, expiry, policy, provider, breaker and token bucket; then **claims** the message with one transaction.
4. It calls the **provider** under a time limiter and breaker, outside any transaction, with an idempotency reference.
5. The outcome decides the next state: `SENT_TO_PROVIDER`, `RETRY_PENDING` (backoff), `FAILED` (maybe fallback), `DELIVERY_UNKNOWN` (do not guess), or `DEAD_LETTERED` after exhaustion.
6. A signed **callback** arrives later; HMAC, replay guard, normaliser and the rank rule move the message to `DELIVERED` or `FAILED` monotonically.
7. If no callback arrives, the **sweeper and reconciliation** resolve the unknown from provider evidence.
8. Everything is **audited** and visible in the console timeline; operators can cancel early, requeue or replay later.

## 23.4 Ten sentences to say in an interview

1. Accepted means persisted, not delivered.
2. The database is the source of truth; the broker is a delivery mechanism.
3. At-least-once plus idempotency gives effectively-once business processing.
4. Exactly-once across a provider boundary is impossible, so ambiguity is a state.
5. Every status change is a compare-and-set through one state machine.
6. Never hold a transaction across a provider call.
7. Retry only what is safe, with jitter, with a cap, with a budget.
8. Policy runs twice; fallback never bypasses consent.
9. Pin template versions; store variables, not rendered text.
10. State what was executed and what was not.
