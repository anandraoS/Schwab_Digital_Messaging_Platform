# 3. Overall System Architecture

## 3.1 Architectural style

The platform is a **modular monolith**: one Spring Boot 3.5 application (`backend/`, Maven module `messaging-platform`, Java 21) whose packages are the modules. Every instance runs the API, the dispatch consumers and the schedulers; instances are identical and stateless, so scaling is "run more of them". The style was chosen over microservices because the central correctness trick of the platform, writing the message state and the outbox row in **one database transaction**, is trivial inside one deployable and painful across services.

Three design moves keep the monolith honest:

1. **Ports and adapters** for everything outside the JVM. `MessageBus`, `CoordinationService`, `ChannelProvider` and `ProviderDiagnosticsStore` are interfaces with an in-memory implementation (profiles `local`, `test`) and a real one (`prod-like`). The same orchestration code runs in both.
2. **A pure state machine.** `com.example.messaging.domain.MessageStateMachine` is a static, table-driven class with no I/O, so the whole lifecycle can be tested exhaustively (`MessageStateMachineTest`).
3. **Compare-and-set ownership.** Whoever wins an `UPDATE ... WHERE status = :expected` owns the next step. This one idea resolves races between cancel and dispatch, between duplicate envelopes, and between late callbacks and reconciliation.

{{diagram:layered}}

## 3.2 Components and responsibilities

{{diagram:components}}

| Package | Responsibility | Notable classes |
|---|---|---|
| `web` | HTTP controllers, DTO records, error mapping | `MessageController`, `GlobalExceptionHandler`, `TemplateController`, `BulkJobController` |
| `security` | AuthN and AuthZ, tenant context, rate limit, body size | `TenantContext`, `AuthorizationService`, `RateLimitFilter`, `RequestSizeFilter`, `DevTokenController` |
| `service` | Use cases and policy | `NotificationService`, `PolicyService`, `RetryPolicy`, `ReconciliationService`, `IdempotencyService`, `AuditService`, `TemplateService`, `PreferenceService` |
| `domain`, `repository` | Entities, state machine, Spring Data JPA | `MessageStateMachine`, `MessageRequest`, `MessageRequestRepository` |
| `messaging` | Bus port, consumer, backpressure, Rabbit adapter | `MessageBus`, `DispatchConsumer`, `InMemoryMessageBus`, `RabbitMessageBus`, `BackpressureGuard` |
| `provider` | Channel ports, simulators, resilience | `ChannelProvider`, `ProviderRegistry`, `ResilientProviderInvoker`, `FaultInjector` |
| `callback` | Webhook authentication and normalisation | `SignatureVerifier`, `ReplayGuard`, `EventNormalizer`, `DeliveryEventProcessor` |
| `batch` | Bulk send with Spring Batch | `BulkJobConfig`, `RecipientRowProcessor`, `BulkMessageWriter`, `BulkJobService` |
| `scheduler` | Outbox, poller, sweeper, retention | `OutboxPublisher`, `ScheduledDispatchPoller`, `StuckMessageSweeper`, `RetentionJob` |
| `coordination` | Redis ports and fallbacks | `CoordinationService`, `RedisCoordination`, `ResilientCoordination` |
| `diagnostics`, `metrics` | Mongo diagnostics, Micrometer meters | `ProviderDiagnosticsStore`, `MessagingMetrics` |
| `template` | Safe renderer | `TemplateRenderer`, `VariableValidator` |

> [!NOTE] Package notes from the deviations record
> `ProviderOutcome` lives in package `provider`, not `domain`. The `coordination` package was added for the Redis ports. `ProviderAdminService` lives in `provider` because it knows the simulators; `service`, `messaging`, `scheduler`, `callback` and `domain` never name a concrete provider (checked by `NewProviderWithoutOrchestrationChangeTest`).

## 3.3 Runtime topology

{{diagram:runtime}}

The provider call executor is separate from the consumer workers on purpose: a provider that hangs for ten seconds ties up executor threads (and trips the circuit breaker), not the workers that hold database transactions and not the Tomcat threads that serve the API.

## 3.4 Key design properties

| # | Property | Mechanism | ADR |
|---|---|---|---|
| 1 | Persist first, publish after | State, outbox and audit committed together; a scheduled publisher moves rows to the bus | 0009 |
| 2 | At-least-once transport, idempotent processing | Four idempotency layers | 0004 |
| 3 | Explicit state machine with CAS ownership | `MessageStateMachine`, CAS repository updates | 0003 |
| 4 | No content or PII on the bus | `DispatchEnvelope` carries identifiers only; variables are stored, rendered text is not | 0007 |
| 5 | Ambiguity is a state | `DELIVERY_UNKNOWN` plus reconciliation | 0010 |
| 6 | Everything bounded | Table 2.3: threads, prefetch, queue depth, body size, retries, bulk concurrency | 0006 |
| 7 | Optional-degradation dependencies | `ResilientCoordination` falls back to the database; Mongo writes are best effort | 0004, 0005 |
| 8 | Ports and adapters | One JVM for development; real brokers in production | 0001, 0002 |

The envelope that travels on the bus is deliberately tiny (`com.example.messaging.messaging.DispatchEnvelope`):

```java
public record DispatchEnvelope(
        String dispatchId,      // messageId:attemptNo, the consumer dedupe key
        String messageId, String tenantId, Channel channel, Priority priority,
        int attemptNo, String correlationId, Instant enqueuedAt) { ... }
```

## 3.5 Technology choices at a glance

| Concern | Choice | Alternative considered | Why |
|---|---|---|---|
| Runtime | Java 21, Spring Boot 3.5.16 | Quarkus, Micronaut | Team familiarity, ecosystem |
| Primary database | PostgreSQL 16; H2 2.x in `MODE=PostgreSQL` for local and test | MySQL, Oracle, NoSQL | ACID plus portable SQL (ADR-0005) |
| Diagnostics store | MongoDB, raw redacted callback payloads only | Store in PostgreSQL | Schema-free vendor payloads, TTL; never on the critical path |
| Cache and coordination | Redis (fast path, token bucket) with in-memory and database fallback | Hazelcast | Lua token bucket, widely available |
| Asynchronous transport | RabbitMQ behind the `MessageBus` port | Kafka | Priorities, per-message TTL, dead-letter exchange, push consumers (ADR-0001) |
| Batch | Spring Batch 5, JDBC repository | Custom loop | Chunking, restart, skip |
| Security | Spring Security 6 resource server, JWT RS256 | Opaque tokens | Stateless, tenant claim |
| Resilience | Resilience4j circuit breaker plus time limiter | Hystrix (retired) | Maintained, composable |
| Observability | Micrometer, Prometheus, OpenTelemetry | Vendor APM | Open standards |
| Frontend | Angular 20, standalone components, Signals, Angular Material 20 | React | Enterprise familiarity |

## Reference material

{{embed:docs/architecture/architecture-overview.md|title=Architecture overview}}

{{embed:docs/architecture/delivery-semantics.md|title=Delivery semantics explainer}}

{{embed:docs/leadership/hld.md|title=High-level design}}
