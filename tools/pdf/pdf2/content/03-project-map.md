# Project Map and Ten-Minute Demo

The best interview answers are anchored in something you built or studied closely. This chapter is a tour of the repository so you can say where anything lives, followed by a ten-minute demonstration script. The demo is a script to practise and to run on your own machine; this guide does not claim that it was run as part of producing the PDF.

## What the platform does

A multi-tenant service that accepts requests to send **e-mail, SMS and push** notifications, applies consent and quiet-hour policy, renders versioned templates, dispatches through provider adapters with retry, circuit breaking and rate limiting, tracks every message through a lifecycle state machine, reconciles uncertain outcomes, and gives operators an Angular console. All providers are simulators.

```diagram {"type":"boxes-and-arrows","direction":"LR","caption":"System context: who talks to what","lanes":[{"id":"c","label":"Callers","color":"blue","nodes":[{"id":"app","label":"Enterprise apps","sub":"role SENDER"},{"id":"ui","label":"Angular console","sub":"operators, authors"}]},{"id":"b","label":"Backend (Spring Boot 3.5, Java 21)","color":"green","nodes":[{"id":"api","label":"REST API","sub":"JWT, tenant scoped"},{"id":"core","label":"Core services","sub":"policy, templates, state machine"},{"id":"pipe","label":"Outbox, bus, consumers","sub":"retry, DLQ, sweeper"},{"id":"cb","label":"Callback endpoint","sub":"HMAC verified"}]},{"id":"d","label":"Data and coordination","color":"orange","nodes":[{"id":"db","label":"Relational database","sub":"H2 local, PostgreSQL target"},{"id":"mq","label":"Message bus","sub":"in-memory local, RabbitMQ target"},{"id":"rd","label":"Coordination","sub":"in-memory local, Redis target"},{"id":"mg","label":"Mongo diagnostics","sub":"best effort"}]},{"id":"p","label":"Providers (simulated)","color":"purple","nodes":[{"id":"pv","label":"Simulated e-mail, SMS and push","sub":"fault injection, signed callbacks"}]}],"edges":[{"from":"app","to":"api","label":"POST /messages"},{"from":"ui","to":"api"},{"from":"api","to":"core"},{"from":"core","to":"db"},{"from":"core","to":"pipe","label":"outbox"},{"from":"pipe","to":"mq"},{"from":"pipe","to":"pv","label":"send"},{"from":"pv","to":"cb","style":"dashed","label":"receipts"},{"from":"cb","to":"core"}]}
```

## Repository tour

| Path | What is there | Interview hook |
|---|---|---|
| `backend/` | Spring Boot 3.5, Java 21, Maven. Packages under `com.example.messaging` | The core of every answer |
| `backend/.../domain` | Entities, `MessageStatus`, `MessageStateMachine`, enums | State machine, optimistic locking |
| `backend/.../service` | `NotificationService`, `IdempotencyService`, `PolicyService`, `RetryPolicy`, `ReconciliationService`, `TemplateService`, `AuditService` | Transactions, idempotency, policy order |
| `backend/.../messaging` | `MessageBus`, `DispatchConsumer`, `DispatchEnvelope`, `BackpressureGuard`, in-memory and RabbitMQ buses | Queue semantics, backpressure |
| `backend/.../scheduler` | `OutboxPublisher`, `StuckMessageSweeper`, `ScheduledDispatchPoller`, `RetentionJob` | Outbox, crash recovery, retention |
| `backend/.../provider` | Provider ports, simulators, `ResilientProviderInvoker`, fault injector | Circuit breaker, timeouts, fault testing |
| `backend/.../callback` | `CallbackController`, `SignatureVerifier`, `ReplayGuard`, payload mappers | Webhook security, ordering |
| `backend/.../batch` | `BulkJobConfig`, reader, processor, writer, `TokenBucketRateLimiter` | Spring Batch, restart, throttling |
| `backend/.../security` | `JwtAuthConverter`, `AuthorizationService`, filters, `DevTokenController` | OAuth2 resource server, tenant isolation |
| `backend/.../web` | Controllers, `GlobalExceptionHandler`, DTOs | REST design, error model |
| `backend/.../coordination` | Redis port with in-memory fallback | Caching, degraded mode |
| `backend/.../diagnostics` | Mongo `ProviderDiagnosticsStore` | When to use MongoDB |
| `backend/src/main/resources/db/migration` | Flyway `V1` to `V200` | Migrations, constraints, indexes |
| `backend/src/main/resources/openapi` and `docs/api/openapi.yaml` | Committed API contract, checked by a contract test | API-first |
| `frontend/messaging-console/` | Angular 20 standalone components, Signals, lazy routes, interceptors | Full-stack story |
| `infrastructure/` | Docker, Compose, Kubernetes base and overlays, Helm chart, RabbitMQ, Postgres, Mongo init | Container assets (not executed) |
| `cloud-foundry/` | Manifests, vars, validator script | PCF story (not executed) |
| `Jenkinsfile`, `bamboo-specs/`, `.github/workflows/` | CI/CD definitions | Pipelines (not executed) |
| `observability/` | Prometheus rules and alerts, Grafana dashboards, OpenTelemetry collector | SLOs, alerting |
| `performance/` | k6 scenarios, Node load driver | Performance testing (not executed) |
| `tests/` | Failure-recovery scripts, UI smoke plan | Resilience testing |
| `specs/` | Spec-driven examples (priority aging, new provider adapter) | Spec-driven development |
| `ai-engineering/` and `AGENTS.md`, `.github/` | Context files, skills, AI code-review exercises, multi-agent workflow | AI-assisted engineering |
| `docs/` | ADRs, architecture flows, API, database, security, operations, runbooks, leadership documents, interview bank | Evidence for every claim |
| `scripts/` | Run, seed, smoke test, static validators | Reproducibility |

### The twelve lifecycle states

`ACCEPTED`, `VALIDATED`, `QUEUED`, `PROCESSING`, `SENT_TO_PROVIDER`, `DELIVERED`, `RETRY_PENDING`, `FAILED`, `DELIVERY_UNKNOWN`, `DEAD_LETTERED`, `REJECTED`, `CANCELLED`. Terminal for the automatic pipeline: `DELIVERED`, `REJECTED`, `CANCELLED`; `FAILED` and `DEAD_LETTERED` can be requeued by an operator only.

### The personas (roles)

| Role | Can do |
|---|---|
| `SENDER` | Submit messages and bulk jobs (bulk also needs scope `messages:bulk`) |
| `TENANT_ADMIN` | Manage a tenant's data, send, read |
| `TEMPLATE_AUTHOR` | Draft and edit template versions |
| `TEMPLATE_APPROVER` | Approve or reject versions (four-eyes: the editor cannot approve) |
| `OPERATOR` | Queues, dead letters, requeue, pause and resume consumers, failed messages |
| `AUDITOR` | Read audit and delivery events |
| `PLATFORM_ADMIN` | Cross-tenant administration, provider config, fault injection (tenant claim `*`) |

## Ten-minute demo script

Prerequisites on the presenter's machine: JDK 21, Maven, Node. Use the JDK by path and do not export global variables (see `AGENTS.md`). Port **18080** is the API, **14200** the Angular dev server. The `local` profile is mandatory: there is deliberately no default profile, so a forgotten profile cannot enable the dev token endpoint.

### Minute 0 to 1: start

```bash
bash scripts/mvn.sh -f backend/pom.xml -DskipTests package     # build the jar once
bash scripts/run-local.sh                                      # profile local, port 18080
```

Say: "Local uses an in-memory database, bus, coordination store and Mongo substitute, with a synthetic seed. The production-like profile switches to PostgreSQL, RabbitMQ, Redis and MongoDB, which I have not run."

### Minute 1 to 2: get a token for a persona

```bash
curl -s -X POST http://localhost:18080/dev/token -H 'Content-Type: application/json' \
  -d '{"subject":"demo-sender","tenant":"tenant-retail","roles":["SENDER"],"scopes":["messages:bulk"]}'
```

Copy `accessToken` into `TOKEN`. Say: "RS256 JWT from an in-memory key; the app validates it with the public key. In production the issuer URI points to the identity provider. The tenant comes only from this token."

### Minute 2 to 4: submit, replay, inspect

```bash
curl -s -i -X POST http://localhost:18080/api/v1/messages \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-0001' \
  -d '{"recipientId":"rcp-001","channel":"EMAIL","category":"TRANSACTIONAL","template":{"code":"TRADE_CONFIRMATION","locale":"en-US"},"variables":{"accountLast4":"1234","amount":"250.00"}}'
# repeat the exact command: 200 with Idempotent-Replay: true, same message id
# change one variable but keep the key: 422 IDEMPOTENCY_KEY_REUSED
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:18080/api/v1/messages/<id>/history
```

Say: "202 means accepted and durable, not delivered. Message, outbox row, idempotency record and audit event commit in one transaction. The history shows each state change with its actor."

### Minute 4 to 6: break a provider on purpose

Get a `PLATFORM_ADMIN` token (tenant `*`), then:

```bash
curl -s -X PUT http://localhost:18080/api/v1/providers/simulated-email/fault-mode \
  -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{"mode":"TRANSIENT","probability":1.0}'
# submit a few messages, then
curl -s -H "Authorization: Bearer $ADMIN" http://localhost:18080/api/v1/ops/summary
curl -s -H "Authorization: Bearer $ADMIN" http://localhost:18080/api/v1/ops/queues
```

Say: "Failures are retried with exponential backoff and jitter; after enough failures the circuit breaker opens and deliveries are requeued without consuming attempts. Try mode `TIMEOUT` or `AMBIGUOUS` and show `DELIVERY_UNKNOWN` instead of `FAILED`: we never blindly resend an e-mail whose outcome is unknown."

### Minute 6 to 7: callbacks and signatures

`POST /dev/simulator/callbacks/emit` makes a simulator post a signed receipt to the callback endpoint. Replay the same event id and show `200 {"result":"replayed"}`; send a body with a wrong signature to show a single generic `401 INVALID_SIGNATURE`.

### Minute 7 to 8: bulk job

```bash
curl -s -X POST http://localhost:18080/api/v1/bulk-jobs/generate-synthetic \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{ ... see openapi.yaml ... }'
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:18080/api/v1/bulk-jobs/<id>
```

Say: "Spring Batch, chunk per transaction, skip limit, rate limited, restartable; a ledger plus idempotency keys make a restart safe."

### Minute 8 to 10: the console

```bash
cd frontend/messaging-console && npm ci && npm start       # Angular dev server with proxy to 18080
```

Log in by choosing a persona, show the dashboard, message timeline, template approval (author then a different approver), queues, dead letters and the audit view. Close with: "Everything you saw runs against simulators. The container, Kubernetes, Cloud Foundry, pipeline and load-test assets exist and are statically validated, but I did not deploy or run them."

> [!NOTE] Where the demo is real
> Endpoints and roles above come from the controllers and the OpenAPI file. The seed data (tenant `tenant-retail`, recipients `rcp-001` and so on, template `TRADE_CONFIRMATION`) is what `scripts/lib/seed-demo.mjs` uses. `scripts/seed-demo.sh` and `scripts/smoke-test.sh` automate a similar flow. If a command differs on your checkout, the OpenAPI file at `docs/api/openapi.yaml` is the authority.

## If you have only two minutes

"It is a multi-tenant omnichannel notification platform. A request is accepted idempotently and durably (message, outbox, audit in one transaction), published by an outbox publisher, consumed with a dedupe claim and compare-and-set state change, sent through a resilient provider adapter with backoff, circuit breaker and rate limit, and tracked to a terminal state by signed callbacks, a sweeper and a reconciliation job. Ambiguous outcomes become `DELIVERY_UNKNOWN`, never a blind retry. I can walk through any of those pieces in the code."
