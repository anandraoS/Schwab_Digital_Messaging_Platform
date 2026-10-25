# Talking Points: Job Themes Mapped to Evidence

A short map from the themes a senior Java engineer interview usually probes to evidence you can point at. The complete line-by-line traceability matrix is a separate document, `docs/traceability/job-description-traceability-matrix.md`; this chapter is the version you can hold in your head.

How to use a row: state the claim in one sentence, name the file or document, then stop and let the interviewer pull the thread. Always include the honest limit in the last column when there is one.

## Engineering themes

| Theme | The one-sentence claim | Evidence in the repository | Honest limit |
|---|---|---|---|
| Java 21 and Spring Boot | Layered Spring Boot 3.5 service with constructor injection, records for configuration and DTOs, bounded executors | `backend/pom.xml`, `config/AsyncConfig`, `config/MessagingProperties` | Built and tested with simulators |
| Concurrency | Bounded pools everywhere, compare-and-set instead of locks, named threads | `AsyncConfig`, `ResilientProviderInvoker`, `BulkJobConfig`, `OutboxEventRepository.markSent` | No production load profile |
| Transactions and Hibernate | Short transactions, REQUIRES_NEW only where a claim must commit alone, optimistic locking for human edits | `IdempotencyService`, `DispatchConsumer.claim`, `@Version` on entities | PostgreSQL behaviour not exercised (H2 in PostgreSQL mode) |
| REST API design | Resource model, 202 async pattern, idempotency keys, structured errors, rate-limit headers, OpenAPI contract test | `docs/api/openapi.yaml`, `web/GlobalExceptionHandler`, `OpenApiContractIT` | Contract is committed and enforced by a test |
| Exception handling | One advice class mapping every exception to a typed `ApiError` with correlation id | `web/GlobalExceptionHandler`, `common/ErrorCode` | |
| Testing | Unit tests for pure logic, slice and integration tests, a pipeline test harness with an adjustable clock and fault hooks | `backend/src/test/java/com/example/messaging/**` | Container-based tests compile under a profile but were not run |

## Messaging and distributed systems

| Theme | Claim | Evidence | Honest limit |
|---|---|---|---|
| Queue semantics and at-least-once | Ack after the durable change; redelivery tolerated | `messaging/MessageBus`, `DispatchConsumer` | RabbitMQ adapter not run against a broker |
| Idempotency | Four layers: API key, message key, dispatch id, provider event id, each enforced by a unique constraint | `V3`, `V4` migrations, `IdempotencyService`, `ReplayGuard` | |
| Outbox | State change and event in one transaction; publisher claims rows with a CAS update | `scheduler/OutboxPublisher`, ADR-0009 | |
| Ordering and out-of-order receipts | Monotonic rank rule; receipts never move state backwards | `MessageStateMachine.resolveReceipt`, `callbacks-normalization.md` | |
| Retry, backoff, DLQ | Exponential backoff with jitter, classified outcomes, dead letter with operator replay | `service/RetryPolicy`, ADR-0006, `web/DeadLetterController` | |
| Backpressure and breakers | Guard before accept, bounded queues, per-provider breaker, token bucket before claim | `BackpressureGuard`, `ResilientProviderInvoker` | |
| Provider timeouts and ambiguity | Timeout is `DELIVERY_UNKNOWN`, reconciled by lookup on the idempotency reference | ADR-0010, `StuckMessageSweeper`, `ReconciliationService` | Simulators only |
| Eventual consistency | `202` means durable, not delivered; states converge through callbacks and reconciliation | `docs/architecture/delivery-semantics.md` | |

## Data

| Theme | Claim | Evidence | Honest limit |
|---|---|---|---|
| Relational modelling | Normalised core with tenant scoping, constraints doing the dedupe | `db/migration/V1` to `V4`, `docs/database/data-model.md` | |
| Oracle awareness | Portable SQL plus a separate Oracle reference DDL and a mapping document | `db/oracle/`, `docs/database/oracle-mapping.md` | Not executed on Oracle; the Oracle DDL was not extended for the bulk ledger |
| MongoDB | Used only for redacted, expiring provider diagnostics | `diagnostics/ProviderDiagnosticsStore`, `docs/database/mongodb-usage.md` | In-memory substitute locally |
| Indexing and plans | Each index tied to a named query | `V50__dispatch_indexes.sql`, `query-plans-and-indexes.md` | No real plans captured |
| Pools, caching, migrations, retention | Fail-fast pool, Redis only as accelerator, expand and contract, batched purge with legal hold | `application-prod-like.yml`, `coordination/`, `docs/database/schema-evolution.md`, `scheduler/RetentionJob` | Not run on production-sized data |

## Cloud, DevOps and operations

| Theme | Claim | Evidence | Honest limit |
|---|---|---|---|
| Docker | Multi-stage, non-root, container-aware JVM flags, tini | `infrastructure/docker/Dockerfile.backend` | Image not built here |
| Cloud Foundry (PCF) | Manifest with health check, services, rolling strategy, blue-green runbook | `cloud-foundry/manifest.yml`, `docs/operations/cloud-foundry-guide.md` | Not pushed |
| Kubernetes | Probes, graceful shutdown, HPA, PDB, network policy, Kustomize and Helm | `infrastructure/kubernetes`, `infrastructure/helm` | Not applied to a cluster |
| CI/CD | Jenkins pipeline with parallel stages, scans and a manual deploy gate; Bamboo spec; GitHub workflow | `Jenkinsfile`, `bamboo-specs/bamboo.yaml`, `.github/workflows/` | Not executed |
| Deployment and rollback | Rolling, blue-green, canary compared; expand-only migrations make rollback safe | `docs/operations/rollback-plan.md`, `ci-cd.md` | |
| Observability and SRE | Metric catalogue, correlation ids, SLIs and SLOs, burn-rate alerts, runbooks | `observability/prometheus/alerts.yml`, `docs/operations/slis-slos.md`, `docs/operations/runbooks/` | Alerts not exercised against a live Prometheus |
| Performance testing | Methodology and eight k6 scenarios plus a Node driver | `performance/`, `docs/operations/performance-tests.md` | **No measurements** are claimed |

## Security

| Theme | Claim | Evidence | Honest limit |
|---|---|---|---|
| OAuth2 and JWT | Stateless resource server, RS256, issuer and expiry validation, role and tenant claims converted and checked | `config/SecurityConfig`, `config/JwtConfig`, `security/JwtAuthConverter` | Dev issuer only locally; production issuer not integrated |
| OWASP API Top 10 | Mapping of each risk to a control | `docs/security/owasp-api-top10-2023-analysis.md` | |
| Tenant isolation | Tenant from token only; foreign resources return 404 | `security/AuthorizationService` | |
| Webhook validation | HMAC-SHA256 over timestamp and raw body, constant-time compare, tolerance window, replay guard, rotation | `callback/SignatureVerifier`, `ReplayGuard` | Dummy secrets only |
| Secrets and data minimisation | No secrets in the repo; envelopes carry no content or PII; redaction before diagnostics | `docs/security/secrets-management.md`, `common/Redaction`, `DispatchEnvelope` | |
| Audit logging | Append-only audit events for every transition and operator action | `service/AuditService`, `docs/architecture` | |
| Secure templates | Declared variables only, HTML escaping for e-mail, header-injection protection | `template/TemplateRenderer` | URL scheme allow-listing is a recommendation |

## AI-assisted engineering

| Theme | Claim | Evidence | Honest limit |
|---|---|---|---|
| Copilot workflows | Instruction files and prompt files that encode the invariants | `.github/copilot-instructions.md`, `.github/instructions/`, `.github/prompts/` | The repository records that it was authored with Claude Code, not Copilot |
| Spec-driven development | Specs with acceptance criteria before code | `specs/`, `ai-engineering/spec-driven-workflow.md` | |
| Reusable context and token economy | Short root `AGENTS.md`, topic context files loaded on demand, skills | `AGENTS.md`, `ai-engineering/context/`, `context-map.md` | |
| Reviewing AI-generated code | Ten flawed-code exercises mapped to invariants, review checklists | `ai-engineering/ai-code-review/`, `checklists/` | |
| Multi-agent work and approval gates | Role model with three gates; author cannot approve sensitive changes | `ai-engineering/multi-agent-workflow.md` | Described as a model, not proven as a measured process |
| Safe refactoring and upgrades | Behaviour pinned by tests, one change at a time, human approval | Chapter "AI-Assisted Safe Refactoring and Dependency Upgrades" | A playbook, not a log |

## Leadership

| Theme | Claim | Evidence |
|---|---|---|
| Design leadership | ADRs for every major decision, risk register, acceptance criteria | `docs/adr/`, `docs/leadership/risk-register.md`, `acceptance-criteria.md` |
| Code review and mentoring | Review scenarios, PR checklist, mentoring examples | `docs/leadership/code-review-scenarios.md`, `pr-checklist.md`, `mentoring-examples.md` |
| Delivery planning | PRD, feature spec, HLD and LLD, sprint breakdown, test strategy | `docs/leadership/` |
| Incident handling | Runbooks and post-incident template | `docs/operations/runbooks/`, `post-incident-review-template.md` |

## Five sentences to rehearse

1. "I treat exactly-once across a provider as impossible and design for at-least-once plus idempotency at every boundary."
2. "Accepted means durable: the message, the outbox row, the idempotency record and the audit event commit in one transaction, and publishing happens after."
3. "A timeout is not a failure; for e-mail and SMS it becomes an unknown outcome that we reconcile instead of resending."
4. "Where two nodes can race, the database decides: unique constraints and compare-and-set updates, not application locks."
5. "I will tell you what I ran and what I only wrote: the container, cluster, pipeline and load-test assets are validated statically, not executed."
