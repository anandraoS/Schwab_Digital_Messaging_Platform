# High-Level Design

Complements `docs/architecture/architecture-overview.md` (diagrams) with decisions, quality attributes and trade-offs.

## 1. Context
Producers (apps via REST), consumers of status (apps, console), external providers (email/SMS/push vendors, simulated), infra (PostgreSQL, RabbitMQ, Redis, MongoDB), observability stack.

## 2. Architectural style
Modular monolith (single Spring Boot module `messaging-platform`, packages per responsibility) deployed as N identical instances running API, consumers and schedulers. Chosen over microservices because the reference needs transactional simplicity (state + outbox in one DB transaction) and a single-JVM local run; module boundaries (packages, ports) keep a later split possible (consumer service, callback service, batch worker).

## 3. Key flows
Submission, dispatch/retry, callbacks, reconciliation, bulk, template approval: see architecture folder.

## 4. Quality attributes and tactics
| Attribute | Tactics | ADR |
|---|---|---|
| Durability | outbox, DB source of truth | 0009 |
| Correctness under duplicates | four-layer idempotency | 0004 |
| Availability | stateless instances, circuit breakers, fallback coordination, graceful shutdown | 0006, 0008 |
| Latency | async accept, no provider call in request, short transactions | 0001 |
| Scalability | competing consumers, priority queues, partition-free DB design, bulk streaming | 0001 |
| Security | JWT, tenant scoping, HMAC callbacks, minimization | 0011 |
| Operability | metrics, runbooks, replay, reconciliation | 0010 |
| Extensibility | ports/adapters | 0002 |
| Auditability | append-only audit, pinned template versions | 0003, 0007 |

## 5. Data
PostgreSQL authoritative; Redis ephemeral; Mongo diagnostics best-effort. See `docs/database/`.

## 6. Capacity thinking (illustrative)
Assume 500 msg/s sustained peak, 50% bursts to 2,000 msg/s via bulk. Consumers: per-message latency ~300 ms provider call => 8 workers per instance ~26 msg/s; 20 instances ~ 500 msg/s (bounded by provider quota, not by us). DB: ~6 short writes per message => ~3,000 writes/s peak; fits a single tuned PostgreSQL primary but retention and partitioning become important. These are back-of-envelope numbers, not measurements.

## 7. Failure modes
Documented in `docs/architecture/reliability-scenarios.md` with proving tests.

## 8. Deployment
Kubernetes primary, CF equivalent (ADR-0008). Profiles local/test/prod-like.

## 9. Alternatives and evolution
Kafka event stream sink from outbox for analytics; CDC outbox; RLS or schema-per-tenant; split consumers; multi-region active/passive.

## 10. Risks
`risk-register.md`.
