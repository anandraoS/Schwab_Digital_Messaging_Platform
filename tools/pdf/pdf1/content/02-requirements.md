# 2. Functional and Non-Functional Requirements

## 2.1 Functional requirements

The functional requirements are derived from the build contract (`docs/design/CONTRACT.md`) and the acceptance criteria (`docs/leadership/acceptance-criteria.md`). The right-hand column names the code that implements each one.

| ID | Requirement | Implemented by |
|---|---|---|
| FR-1 | Accept a message for EMAIL, SMS or PUSH through `POST /api/v1/messages` and answer 202 without calling a provider | `web/MessageController`, `service/NotificationService` |
| FR-2 | Treat `Idempotency-Key` plus tenant as an idempotency scope; identical replays return the original response | `service/IdempotencyService`, table `idempotency_record` |
| FR-3 | Enforce consent, suppression, category rules and quiet hours | `service/PolicyService` |
| FR-4 | Render templates with a pinned, approved version; reject missing variables | `template/TemplateRenderer`, `template/VariableValidator`, `service/TemplateService` |
| FR-5 | Dispatch asynchronously with priorities, retries, backoff, jitter and a dead-letter queue | `scheduler/OutboxPublisher`, `messaging/DispatchConsumer`, `service/RetryPolicy` |
| FR-6 | Route through a provider abstraction with circuit breaker, timeout and rate limit | `provider/ChannelProvider`, `provider/ResilientProviderInvoker`, `provider/ProviderRegistry` |
| FR-7 | Receive signed provider callbacks, deduplicate, order them and apply them monotonically | `callback/*` |
| FR-8 | Resolve ambiguous outcomes by callback or provider lookup, never by blind retry | `scheduler/StuckMessageSweeper`, `service/ReconciliationService` |
| FR-9 | Allow cancel (only before processing), operator requeue and dead-letter replay or discard | `service/NotificationService`, `service/DefaultDeadLetterOperations`, `service/ReplayService` |
| FR-10 | Provide bulk sends from CSV with skip, restart and throttling | `batch/*` |
| FR-11 | Manage templates with versions, locales, preview and four-eyes approval | `web/TemplateController`, `service/TemplateService` |
| FR-12 | Manage recipient preferences with optimistic locking, and suppressions | `service/PreferenceService`, `web/RecipientController` |
| FR-13 | Provide operations views: queues, summary, providers, failed messages, audit and delivery events | `web/OpsController`, `web/ProviderController`, `web/EventController` |
| FR-14 | Record an append-only audit trail for every state change and sensitive action | `service/AuditService`, table `audit_event` |
| FR-15 | Serve a role-aware Angular console | `frontend/messaging-console/src/app` |

## 2.2 Non-functional requirements

| ID | Quality | Requirement (specified unless marked) | Tactic |
|---|---|---|---|
| NFR-1 | Durability | No accepted message is lost (SLO target 99.99 percent, illustrative) | Transactional outbox, DB as source of truth (ADR-0009) |
| NFR-2 | Correctness | Duplicates produce at most one business effect | Four-layer idempotency (ADR-0004) |
| NFR-3 | Latency | Submit p95 under 300 ms (illustrative target) | No provider call on the request thread, short transactions |
| NFR-4 | Availability | API 99.9 percent monthly (illustrative) | Stateless instances, circuit breakers, graceful shutdown |
| NFR-5 | Scalability | Competing consumers, priority queues, streaming bulk | `MessageBus` port, chunked Spring Batch |
| NFR-6 | Security | Tenant isolation; no PII in logs or on the bus; HMAC callbacks | ADR-0011, `common/Redaction`, `callback/SignatureVerifier` |
| NFR-7 | Operability | Metrics, 19 alerts, the runbooks, replay, reconciliation | `metrics/MessagingMetrics`, `observability/`, `docs/operations/runbooks/` |
| NFR-8 | Extensibility | A new provider needs no orchestration change | `NewProviderWithoutOrchestrationChangeTest` |
| NFR-9 | Portability | Same SQL on H2, PostgreSQL; Oracle mapping documented | Flyway portable SQL rule |
| NFR-10 | Auditability | Reconstruct any message history | `GET /messages/{id}/history`, pinned template versions |
| NFR-11 | Resource bounds | Everything bounded: threads, prefetch, queue depth, request size, retries, bulk concurrency | See table 2.3 |

## 2.3 Bounded everything: the specified limits

The platform makes unbounded growth impossible by giving every resource a limit. These are the shipped defaults in `backend/src/main/resources/application.yml`.

| Resource | Key | Default |
|---|---|---|
| Dispatch worker threads | `messaging.dispatch.concurrency` | 8 |
| Consumer prefetch | `messaging.dispatch.prefetch` | 16 |
| Max delivery attempts | `messaging.dispatch.max-attempts` | 5 |
| Backoff base and cap | `backoff-base-ms`, `backoff-cap-ms` | 1000 ms, 60000 ms |
| Poison threshold | `poison-threshold` | 5 deliveries |
| Delivery receipt timeout | `receipt-timeout` | 15 minutes |
| Reconcile after | `reconcile-after` | 5 minutes |
| Lock TTL for PROCESSING | `lock-ttl` | 2 minutes |
| Backpressure | `messaging.backpressure.max-queue-depth` | 5000 |
| API rate limit | `messaging.api.rate-per-sec`, `burst` | 50 per second, burst 100 |
| Request body | `messaging.api.max-body-bytes` | 262144 (256 KB) |
| Bulk concurrency, chunk, skip limit, rows | `messaging.bulk.*` | 4, 500, 100, 100000 |
| Retention | `messaging.retention.*` | messages 90 d, audit 365 d, events 30 d |
| Callback tolerance | `messaging.callback.tolerance` | 5 minutes |
| Callback replay window | `messaging.pipeline.replay-window` | 24 hours |
| Circuit breaker | `messaging.pipeline.resilience.*` | 50 percent, window 20, min 10 calls, 5 s open, 3 probes |

## 2.4 Out-of-scope but considered

Real vendor contracts, deliverability reputation management, campaign segmentation, in-app inbox and multi-region active/active are explicitly out of scope. Chapter 26 lists them with the future enhancement that would address each.

## Reference material

{{embed:docs/leadership/acceptance-criteria.md|title=Acceptance criteria|skip=Traceability}}

{{embed:docs/leadership/test-strategy.md|title=Test strategy|skip=Traceability}}
