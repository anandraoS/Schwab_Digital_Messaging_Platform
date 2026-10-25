# Build Contract — Enterprise Omnichannel Messaging and Notification Orchestration Platform

> Note (2026-10-18): this is the initial build plan. Where it disagrees with `DEVIATIONS.md` (what was actually built) or the code, `DEVIATIONS.md` and the code win; for example policy rejections are `202 REJECTED`, a replayed callback is `200`, `DUPLICATE_RESOURCE` exists, `ProviderOutcome` lives in package `provider`, and suppression is per tenant. See `CONSISTENCY_LOG.md`.

Single source of truth for every contributor (human or agent). If you must deviate, record it in
`docs/design/DEVIATIONS.md` (append only) instead of silently changing shared decisions.
All data is synthetic. No real provider is ever called. Never claim something ran unless it ran.
The platform is *inspired by* an enterprise messaging role; it does not reproduce any employer's internal systems.

## 0. Machine/tooling facts (Windows 11, Git Bash)
- JDK 21: `C:\Users\sahukari.rao\.jdks\graalvm-jdk-21.0.7` (`JAVA_HOME` already set in session settings; never export globally, never `git push`).
- Maven 3.9.12: `~/.m2/wrapper/dists/apache-maven-3.9.12-bin/5nmfsn99br87k5d4ajlekdq10k/apache-maven-3.9.12/bin/mvn` (call `JAVA_HOME=/c/Users/sahukari.rao/.jdks/graalvm-jdk-21.0.7 <mvn> -B ...`).
- Node 24, npm 11, Python 3.12 (`python -I`). **No Docker, no kubectl, no k6, no cloud.** Docker/K8s/CF/CI/k6 assets are statically validated only and must be labelled "not executed".
- Port 8080 is occupied on this machine: local runs use **18080** (API) and 14200 (Angular dev server).
- Pass node paths as Windows-style (`cygpath -m`). Avoid `rm`, `cd &&` chains, inline `curl` with credentials; put such logic in script files. Git root is the home dir: never `git add -A`.
- Repo root: `C:\Users\sahukari.rao\Downloads\prepearation\schwab-digital-messaging-platform` (called `<root>`).

## 1. Fixed technology choices (ADR-backed)
| Concern | Choice | Notes |
|---|---|---|
| Language/runtime | Java 21, Spring Boot **3.5.16** (parent BOM), Maven | `backend/pom.xml` single module `messaging-platform` |
| Primary DB | **PostgreSQL 16** (ADR-0005). Local/test: **H2 2.x in `MODE=PostgreSQL`** | Flyway migrations must run on both: no PG-only syntax (no partial indexes, no JSONB, no `ON CONFLICT`; use plain SQL, `TIMESTAMP`, `VARCHAR`, `CLOB`/`TEXT` via `VARCHAR(4000)` etc.). Oracle mapping documented in `docs/database/oracle-mapping.md` + `backend/src/main/resources/db/oracle/` reference DDL |
| NoSQL | **MongoDB**, only for provider diagnostics / raw callback payload archive (`provider_diagnostics` collection). Local/test: `de.bwaldvogel:mongo-java-server` 1.47.0 in-memory (profile `local`,`test`) | Failure to write Mongo must never fail message processing (best-effort, metric `messaging.diagnostics.write.failures`) |
| Cache/coordination | **Redis**: idempotency fast-path, token-bucket provider rate limit, API rate limit. Port interfaces + Lettuce/Spring Data Redis adapter (Lua token bucket) + in-memory adapter for local/test. `ResilientCoordination` decorator falls back to DB/in-memory when Redis is down | |
| Async | **RabbitMQ** primary (ADR-0001: priority queues, per-message TTL/DLX, simple ops, push-based consumers; Kafka preferable for replay/stream analytics/huge fan-out) | Port `MessageBus` with `RabbitMessageBus` (prod profile `rabbit`) and `InMemoryMessageBus` (profiles `local`,`test`, bounded priority queue, worker threads, same semantics: ack/nack/requeue-with-delay/DLQ). Rabbit topology in `infrastructure/rabbitmq/definitions.json` and `RabbitTopologyConfig` |
| Batch | Spring Batch 5, JDBC job repository (same DB), chunk-oriented, restartable | |
| Security | Spring Security 6 OAuth2 resource server (JWT, RS256). Roles: `ROLE_PLATFORM_ADMIN`, `ROLE_TENANT_ADMIN`, `ROLE_TEMPLATE_AUTHOR`, `ROLE_TEMPLATE_APPROVER`, `ROLE_OPERATOR`, `ROLE_SENDER` (service client), `ROLE_AUDITOR`. Claims: `sub`, `roles` (array), `tenant` (string tenant id; `*` only for platform admin), `scope` | Local profile exposes `POST /dev/token` (dev-only, disabled outside `local`/`test`) that mints RS256 JWTs from an in-memory generated key and the app validates with the matching public key (JWKS-style `NimbusJwtDecoder.withPublicKey`). Prod profile uses `spring.security.oauth2.resourceserver.jwt.issuer-uri` |
| Callbacks | HMAC-SHA256 signature `X-Signature: t=<epoch>,v1=<hex>` over `t + "." + rawBody`, headers `X-Provider-Key-Id`, `X-Event-Id`; 5 min tolerance; replay cache by `X-Event-Id`; secrets are dummy and loaded from config | Callback endpoints are `permitAll` at the Spring Security layer but signature-validated in a filter/service (never trust the network) |
| Resilience | Resilience4j (circuit breaker + time limiter via executor) around provider calls; bounded `ThreadPoolExecutor` for consumers | |
| Observability | Micrometer + Prometheus registry; `micrometer-tracing-bridge-otel` + OTLP exporter (endpoint configurable, disabled locally); JSON logs via logback `LogstashEncoder`-free custom pattern (JSON via Spring Boot 3.4+ `logging.structured.format.console=ecs` is acceptable); MDC `correlationId`, `tenant`, `messageId` | |
| API docs | springdoc-openapi 2.8.x; committed spec at `backend/src/main/resources/openapi/messaging-api-v1.yaml` and exported copy at `docs/api/openapi.yaml`; a test asserts springdoc output contains every path in the committed spec |
| Frontend | Angular **20** (standalone components, Signals, lazy routes, Reactive Forms, HttpClient, Angular Material 20), Karma/Jasmine or Jest-free default test runner (use Angular CLI default: Karma+Jasmine), Playwright optional e2e script using plain node `fetch`-based API e2e in `tests/end-to-end/` |
| Perf | k6 scripts in `performance/k6/` (not executed here), plus a Java in-JVM load harness `backend/src/test/java/.../perf/LoadHarnessIT` or `scripts` that **can** be executed locally; record measurements only if executed |

## 2. Backend package layout (`backend/src/main/java/com/example/messaging/...`)
```
MessagingPlatformApplication
common/        ErrorCode, ApiError, ApiException(+subclasses), CorrelationIdFilter, Clock bean, Ids, Redaction, PageResponse
config/        SecurityConfig, JwtConfig(dev issuer), RabbitTopologyConfig(@Profile rabbit), MongoConfig, AsyncConfig, OpenApiConfig, PropertiesConfig (@ConfigurationProperties records)
security/      TenantContext, CurrentPrincipal, AuthorizationService (resource-level checks), RateLimitFilter, RequestSizeFilter, DevTokenController
domain/        enums + entities + state machine (pure Java where possible)
repository/    Spring Data JPA repositories (no N+1: fetch joins / @EntityGraph / projections)
service/       NotificationService (submission/query/cancel), TemplateService, PreferenceService, PolicyService (consent/quiet hours), RoutingService, DispatchService (consumer-side processing), RetryPolicy, CallbackService, ReconciliationService, AuditService, IdempotencyService
template/      TemplateRenderer (safe, allow-listed `{{var}}` engine, HTML-escaping for email HTML, no expression evaluation), VariableValidator
provider/      EmailProvider, SmsProvider, PushNotificationProvider, ProviderRegistry, ProviderResult(+outcome enum), simulators (SimulatedEmailProvider, SimulatedSmsProvider, SimulatedPushProvider, FaultInjector, a 4th "AcmeSmsProvider" added as the worked "new provider" example), ResilientProviderInvoker
messaging/     MessageBus, DispatchEnvelope, InMemoryMessageBus, RabbitMessageBus, DispatchConsumer, BusMetrics, DeadLetterService, ReplayService
callback/      CallbackController, SignatureVerifier, ReplayGuard, EventNormalizer (+ per-provider payload mappers), DeliveryEventProcessor
batch/         BulkJobConfig, RecipientCsvReader, validation processor, dispatch writer, BulkJobService, SyntheticDatasetGenerator, BulkJobController
diagnostics/   ProviderDiagnosticsStore (Mongo), DiagnosticsController
web/           REST controllers + DTO records (request/response) + GlobalExceptionHandler
scheduler/     ScheduledDispatchPoller (due scheduled messages), OutboxPublisher, StuckMessageSweeper (marks long SENT_TO_PROVIDER/PROCESSING as DELIVERY_UNKNOWN / re-queues), RetentionJob
metrics/       MessagingMetrics (all custom meters)
```
Tests mirror these packages under `backend/src/test/java`. Integration tests extend `AbstractIntegrationTest` (`@SpringBootTest`, profile `test`, random port off, H2 db name unique per context). Testcontainers-based tests (`*ContainerIT`: PostgreSQL, RabbitMQ, Redis, Mongo) use `@Testcontainers(disabledWithoutDocker = true)` and are in Maven profile `-Pcontainers` (not executed here).

## 3. Domain model

### 3.1 Message lifecycle (`MessageStatus`)
`ACCEPTED → VALIDATED → QUEUED → PROCESSING → SENT_TO_PROVIDER → DELIVERED` plus
`REJECTED, RETRY_PENDING, FAILED, DEAD_LETTERED, CANCELLED, DELIVERY_UNKNOWN`. Also `SCHEDULED` is **not** a status: scheduled messages are `VALIDATED` with `scheduled_at` in the future and not yet enqueued (outbox/poller enqueues when due).

Transition table (enforced in `MessageStateMachine`; illegal ⇒ `IllegalStateTransitionException` ⇒ HTTP 409 where API-driven, logged+audited where event-driven):
| From | Allowed to | Owner/actor |
|---|---|---|
| ACCEPTED | VALIDATED, REJECTED | submission service (synchronous) |
| VALIDATED | QUEUED, CANCELLED, REJECTED (policy suppression decided at dispatch time ⇒ REJECTED with reason) | outbox/poller; API cancel |
| QUEUED | PROCESSING, CANCELLED | dispatch consumer; API cancel |
| PROCESSING | SENT_TO_PROVIDER, RETRY_PENDING, FAILED, DELIVERY_UNKNOWN, REJECTED | dispatch consumer |
| RETRY_PENDING | QUEUED (backoff elapsed, via bus delayed redelivery/poller), DEAD_LETTERED, CANCELLED | retry scheduler; operator |
| SENT_TO_PROVIDER | DELIVERED, FAILED (provider bounce/permanent failure callback), DELIVERY_UNKNOWN (no receipt within `deliveryReceiptTimeout`) | callback processor; sweeper |
| DELIVERY_UNKNOWN | DELIVERED, FAILED (late callback / reconciliation) | callback processor; ReconciliationService |
| FAILED | DEAD_LETTERED, QUEUED (operator "requeue" action only) | operator |
| DEAD_LETTERED | QUEUED (operator replay only) | operator |
| DELIVERED, REJECTED, CANCELLED | — terminal | |
`FAILED` is "terminal unless operator action"; `DEAD_LETTERED` is "terminal unless operator replay". Cancellation is permitted only from VALIDATED, QUEUED, RETRY_PENDING. Provider acceptance (`SENT_TO_PROVIDER`) never implies delivery. Out-of-order callbacks: a `DELIVERED` receipt arriving while status is `PROCESSING`/`DELIVERY_UNKNOWN`/`SENT_TO_PROVIDER` is applied (monotonic rank rule, see 3.4); a `SENT`/`ACCEPTED_BY_PROVIDER` receipt after `DELIVERED` is recorded but ignored.

### 3.2 Enums
`Channel{EMAIL,SMS,PUSH}`, `Priority{CRITICAL(9),HIGH(6),NORMAL(3),LOW(1)}` (RabbitMQ `x-max-priority=9`), `Category{TRANSACTIONAL,SECURITY_ALERT,SERVICE,MARKETING}` (TRANSACTIONAL, SECURITY_ALERT, SERVICE = non-marketing "transactional-class"; only MARKETING requires opt-in and is suppressed by opt-out and quiet hours), `TemplateStatus{DRAFT,PENDING_APPROVAL,PUBLISHED,RETIRED}`, `DeliveryEventType{PROVIDER_ACCEPTED,SENT,DELIVERED,BOUNCED,FAILED,REJECTED,DEFERRED,OPENED,CLICKED,UNSUBSCRIBED}`, `ProviderOutcome{ACCEPTED,TRANSIENT_FAILURE,PERMANENT_FAILURE,RATE_LIMITED,TIMEOUT,AMBIGUOUS}`, `BulkJobStatus{CREATED,RUNNING,COMPLETED,COMPLETED_WITH_ERRORS,FAILED,STOPPED,ABANDONED}` , `AuditAction` (string constants), `DlqReason{RETRIES_EXHAUSTED,POISON_MESSAGE,PERMANENT_FAILURE,OPERATOR}`.

### 3.3 Business policy (PolicyService, order matters; see docs/design/policy.md)
1. Recipient must exist for tenant and not be `deleted`.
2. Global suppression list (hard bounce / complaint / regulatory) blocks **all** categories on that channel → REJECTED(`SUPPRESSED`).
3. Category rules: MARKETING needs explicit channel opt-in AND `marketingOptIn=true`; opt-out ⇒ REJECTED(`OPTED_OUT`). Non-marketing categories ignore marketing opt-out but still respect the *channel hard-block* from step 2 and per-channel `enabled` only for SERVICE (TRANSACTIONAL and SECURITY_ALERT go to any channel the recipient has a valid address for).
4. Quiet hours (recipient time zone, default 21:00–08:00): MARKETING and LOW/NORMAL SERVICE are **deferred** (`scheduled_at` moved to quiet-hours end, status remains VALIDATED); TRANSACTIONAL/SECURITY_ALERT with priority ≥ HIGH ignore quiet hours for SMS/PUSH? — rule: SECURITY_ALERT always sends; TRANSACTIONAL sends unless channel is SMS/PUSH and priority is LOW.
5. Fallback: request may list `fallbackChannels`; each fallback is evaluated through steps 1–4 independently — **fallback never bypasses consent**. Fallback is attempted only on PERMANENT_FAILURE/RETRIES_EXHAUSTED of the primary and creates a *new attempt on a new channel* recorded on the same message (`channel_used`).
6. Idempotency: `Idempotency-Key` header (required for POST /messages) + tenant ⇒ same request hash returns original response (HTTP 200 + `Idempotent-Replay: true`); same key with different hash ⇒ 422 `IDEMPOTENCY_KEY_REUSED`; in-flight duplicate ⇒ 409 `IDEMPOTENCY_IN_PROGRESS`.

### 3.4 Delivery-event handling
Dedup key: `(provider_id, provider_event_id)` unique. Rank: PROVIDER_ACCEPTED/SENT=1, DEFERRED=1, DELIVERED=3, BOUNCED/FAILED/REJECTED=3 (terminal-negative); a lower rank never overwrites a higher rank. DELIVERED vs later BOUNCED conflict ⇒ keep first terminal, audit `DELIVERY_EVENT_CONFLICT`. Event `occurred_at` older than current status timestamp is recorded (`applied=false`, `ignored_reason=OUT_OF_ORDER|STALE|DUPLICATE`).

## 4. Database schema (Flyway `backend/src/main/resources/db/migration`, V1__… ordered)
Tables (all with `id` VARCHAR(36) UUID pk unless noted, `created_at`, `updated_at`, `version BIGINT` for optimistic locking where marked ◆):
`tenant`(id, name, status) ; `recipient`◆ (id, tenant_id, external_ref UNIQUE per tenant, email, phone_e164, push_token, locale, time_zone, deleted, …) — synthetic, `recipient_preference`◆ (recipient_id pk-ish, marketing_opt_in, email_enabled, sms_enabled, push_enabled, quiet_start, quiet_end, fallback_order VARCHAR) ; `suppression`(tenant_id, channel, address_hash, reason, UNIQUE(tenant_id,channel,address_hash)) ; `template`(id, tenant_id, code, channel, category) UNIQUE(tenant_id,code,channel) ; `template_version`◆ (template_id, version_no, locale, status, subject, body, variables_json, created_by, approved_by, published_at, UNIQUE(template_id,version_no,locale)) ; `message_request`◆ (id, tenant_id, client_app, recipient_id, channel, category, priority, status, template_code, template_version_no, template_locale, rendered_subject?, payload_json (variables, **redacted at rest policy: store variables not rendered body**), correlation_id, idempotency_key, request_hash, scheduled_at, expires_at, attempt_count, max_attempts, next_attempt_at, channel_used, status_reason, bulk_job_id NULL, created_at…, UNIQUE(tenant_id, idempotency_key)) with indexes (tenant_id,status,created_at DESC), (recipient_id,created_at), (status,next_attempt_at), (status, scheduled_at), (correlation_id), (bulk_job_id) ; `message_attempt`(id, message_id, attempt_no, channel, provider_id, outcome, provider_message_id, error_code, latency_ms, started_at, finished_at; UNIQUE(message_id,attempt_no)) ; `provider_config`(id, provider_id UNIQUE, channel, enabled, priority_order, rate_limit_per_sec, timeout_ms, hmac_key_ref, circuit_state info?) ; `delivery_event`(id, message_id, provider_id, provider_event_id, type, occurred_at, received_at, applied, ignored_reason, raw_ref; UNIQUE(provider_id,provider_event_id)) ; `bulk_job`◆(id, tenant_id, name, status, total, processed, succeeded, failed, duplicate, file_name, rate_limit_per_sec, created_by, started_at, finished_at, spring_batch_execution_id) + `bulk_job_failure`(job_id, line_no, reason, raw_line_hash) ; `audit_event`(id, tenant_id, actor, actor_role, action, entity_type, entity_id, correlation_id, detail_json, occurred_at; append-only, indexes (entity_type,entity_id,occurred_at), (tenant_id,occurred_at)) ; `idempotency_record`(id, tenant_id, idem_key, request_hash, status IN_PROGRESS|COMPLETED, response_status, response_body, message_id, expires_at, UNIQUE(tenant_id,idem_key)) ; `processed_message`(consumer dedupe: dispatch_id UNIQUE) ; `outbox_event`(id, aggregate_id, topic, payload, status NEW|SENT, created_at, sent_at) ; `replay_guard`(event_id pk, seen_at) ; `shedlock`-free scheduling uses DB row claim `UPDATE … WHERE status=… ` (compare-and-set) instead of ShedLock; Spring Batch schema tables via its own migration `V100__spring_batch.sql` (use the H2/PG-compatible schema scripts).
Seed data (`R__seed_synthetic.sql` or `V900__synthetic_seed.sql` loaded only under profiles `local,test` via `spring.flyway.locations` extension): 2 tenants (`tenant-retail`, `tenant-wealth`), 60 recipients (synthetic names like "Recipient 001", emails `recipient001@example.test`, phones `+1555010xxxx`), preferences mix, 8 templates in en/es, 4 provider configs.

## 5. REST API (base `/api/v1`, JSON, `application/json`, errors `application/problem+json`-style `ApiError`)
Auth: Bearer JWT on everything except `/api/v1/callbacks/**` (HMAC), `/actuator/health/**`, `/actuator/prometheus` (restricted by network in prod; permitted locally), `/dev/token` (local), swagger/openapi docs (local).
Tenant isolation: non-admin principals only see/modify their `tenant` claim's data → else 404 (not 403) for resources owned by another tenant. `PLATFORM_ADMIN` (`tenant=*`) can pass `X-Tenant-Id` header to scope, or sees all.

Error payload `ApiError`: `{timestamp, status, error, code, message, path, correlationId, details:[{field, message}]}`. Codes: `VALIDATION_FAILED, MALFORMED_REQUEST, UNSUPPORTED_CHANNEL, UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, IDEMPOTENCY_KEY_REUSED, IDEMPOTENCY_IN_PROGRESS, ILLEGAL_STATE_TRANSITION, TEMPLATE_NOT_FOUND, TEMPLATE_VARIABLE_MISSING, TEMPLATE_RENDER_ERROR, RECIPIENT_NOT_FOUND, POLICY_SUPPRESSED, RATE_LIMITED, PAYLOAD_TOO_LARGE, CONFLICT_OPTIMISTIC_LOCK, INVALID_SIGNATURE, REPLAYED_EVENT, INTERNAL_ERROR, SERVICE_UNAVAILABLE`.
Headers: `X-Correlation-Id` (accepted/generated, echoed), `Idempotency-Key`, `X-RateLimit-Limit/Remaining/Reset`, `Retry-After` on 429, `API-Version: 1`.
Limits: body ≤ 256 KB (413), per-client rate limit default 50 req/s burst 100 (configurable), page size ≤ 200.

Endpoints (roles in brackets; `*` = any authenticated):
```
POST   /api/v1/messages                      [SENDER,TENANT_ADMIN,PLATFORM_ADMIN]  202 Accepted (+Location) ; body below
GET    /api/v1/messages/{id}                 [*]  full detail incl. attempts + deliveryEvents summary
GET    /api/v1/messages                      [*]  filters: status,channel,category,priority,recipientId,correlationId,from,to,q(client ref), page,size,sort ; PageResponse
GET    /api/v1/messages/{id}/status          [*]  lightweight {id,status,statusReason,channelUsed,attemptCount,updatedAt,terminal}
GET    /api/v1/messages/{id}/history         [*]  lifecycle timeline = audit_events + attempts + delivery events merged & ordered
POST   /api/v1/messages/{id}/cancel          [SENDER,TENANT_ADMIN,OPERATOR,PLATFORM_ADMIN] 200 or 409
POST   /api/v1/messages/{id}/requeue         [OPERATOR,PLATFORM_ADMIN] FAILED|DEAD_LETTERED → QUEUED
GET    /api/v1/dead-letters                  [OPERATOR,TENANT_ADMIN,PLATFORM_ADMIN] list DEAD_LETTERED (+reason) paged
POST   /api/v1/dead-letters/{messageId}/replay [OPERATOR,PLATFORM_ADMIN]
POST   /api/v1/dead-letters/{messageId}/discard [OPERATOR,PLATFORM_ADMIN] (audit; stays DEAD_LETTERED with discarded flag)
GET    /api/v1/failed-messages               [OPERATOR,TENANT_ADMIN,PLATFORM_ADMIN] FAILED/DELIVERY_UNKNOWN/RETRY_PENDING investigation list
GET    /api/v1/delivery-events               [OPERATOR,AUDITOR,TENANT_ADMIN,PLATFORM_ADMIN] search: messageId,providerId,type,from,to,applied,page,size
GET    /api/v1/audit-events                  [AUDITOR,TENANT_ADMIN,PLATFORM_ADMIN] filters: entityType,entityId,action,actor,from,to
GET    /api/v1/templates                     [*]  filters channel,status,code
POST   /api/v1/templates                     [TEMPLATE_AUTHOR,TENANT_ADMIN] create template + first DRAFT version
GET    /api/v1/templates/{id}                [*] with versions
POST   /api/v1/templates/{id}/versions       [TEMPLATE_AUTHOR,TENANT_ADMIN] new DRAFT version (locale variant)
PUT    /api/v1/templates/{id}/versions/{versionNo}?locale=en [TEMPLATE_AUTHOR] edit DRAFT only (optimistic lock via If-Match / version field)
POST   /api/v1/templates/{id}/versions/{versionNo}/submit?locale=  [TEMPLATE_AUTHOR] DRAFT→PENDING_APPROVAL
POST   /api/v1/templates/{id}/versions/{versionNo}/approve?locale= [TEMPLATE_APPROVER] PENDING→PUBLISHED (approver != author, four-eyes); previously published version of same locale → RETIRED
POST   /api/v1/templates/{id}/versions/{versionNo}/reject?locale=  [TEMPLATE_APPROVER] PENDING→DRAFT with comment
POST   /api/v1/templates/{id}/versions/{versionNo}/retire?locale=  [TEMPLATE_APPROVER,TENANT_ADMIN]
POST   /api/v1/templates/preview             [TEMPLATE_AUTHOR,TENANT_ADMIN,OPERATOR] {channel,subject,body,variables,sampleData,locale} → rendered output / validation errors (no send)
GET    /api/v1/templates/{id}/history        [*] change history from audit
GET    /api/v1/recipients                    [OPERATOR,TENANT_ADMIN,PLATFORM_ADMIN,SENDER] q, page, size
GET    /api/v1/recipients/{id}/preferences   [OPERATOR,TENANT_ADMIN,PLATFORM_ADMIN,SENDER]
PUT    /api/v1/recipients/{id}/preferences   [TENANT_ADMIN,OPERATOR,PLATFORM_ADMIN] body includes `version` ⇒ 409 CONFLICT_OPTIMISTIC_LOCK on stale write
POST   /api/v1/recipients/{id}/suppressions  [TENANT_ADMIN,OPERATOR] / DELETE .../suppressions/{channel}
GET    /api/v1/providers                     [OPERATOR,TENANT_ADMIN,PLATFORM_ADMIN] provider health: id, channel, enabled, circuitState, successRate, p95LatencyMs, rateLimit, lastError, simulated fault mode
PUT    /api/v1/providers/{providerId}/config [PLATFORM_ADMIN] enable/disable, rate limit, timeout
PUT    /api/v1/providers/{providerId}/fault-mode [PLATFORM_ADMIN, profile local/test only] set simulator mode {NONE,TRANSIENT,PERMANENT,RATE_LIMIT,TIMEOUT,AMBIGUOUS,ALTERNATING} + probability
GET    /api/v1/ops/queues                    [OPERATOR,PLATFORM_ADMIN] queue depth per priority, in-flight, consumers, DLQ depth, retry-pending count, oldest-message age
GET    /api/v1/ops/summary                   [*] dashboard aggregates: counts by status/channel last 24h, success rate, p95 dispatch delay
POST   /api/v1/ops/consumers/pause|resume    [PLATFORM_ADMIN]
POST   /api/v1/bulk-jobs                     [SENDER,TENANT_ADMIN,PLATFORM_ADMIN] multipart CSV (file) + params (name, templateCode, channel, category, ratePerSecond) → 202 ; bulk send needs role SENDER + scope `messages:bulk` ; max rows from config
POST   /api/v1/bulk-jobs/generate-synthetic  [TENANT_ADMIN,PLATFORM_ADMIN,SENDER] {rows, duplicateRate, invalidRate} creates & submits a synthetic job without upload
GET    /api/v1/bulk-jobs , /api/v1/bulk-jobs/{id} , /api/v1/bulk-jobs/{id}/failures (paged) ; POST /api/v1/bulk-jobs/{id}/stop , /restart
GET    /api/v1/diagnostics/messages/{id}     [OPERATOR,PLATFORM_ADMIN] provider diagnostics docs from Mongo
POST   /api/v1/callbacks/{providerId}        HMAC-authenticated provider webhook (raw provider-specific JSON; normalized by EventNormalizer)
POST   /dev/simulator/callbacks/emit         [local/test; PLATFORM_ADMIN] makes the simulator POST a signed callback to itself (for demos/tests) {messageId,type,delayMs,duplicate,outOfOrder}
GET    /api/v1/me                            [*] principal info (roles, tenant) used by the Angular role-aware UI
```
`POST /messages` body (`SubmitMessageRequest`):
```json
{
 "clientReference": "order-8841-confirm",
 "recipientId": "rcp-001"            // or "recipient": {"externalRef":"CUST-001"}
 "channel": "EMAIL|SMS|PUSH",
 "category": "TRANSACTIONAL",
 "priority": "NORMAL",
 "template": {"code":"TRADE_CONFIRMATION","locale":"en-US","version":null},   // null version ⇒ pin latest PUBLISHED at accept time (ADR-0007)
 "variables": {"accountLast4":"1234","amount":"250.00"},
 "channelOptions": { "email":{"replyTo":"…","headers":{…}}, "sms":{"senderId":"ACME"}, "push":{"collapseKey":"…","ttlSeconds":3600} },
 "fallbackChannels": ["SMS"],
 "schedule": {"sendAt":"2026-11-01T14:00:00Z","expiresAt":"…"},
 "metadata": {"source":"brokerage-web"}
}
```
Response 202 `MessageAcceptedResponse {id,status,channel,correlationId,scheduledAt,links}`.
`PageResponse<T> {content:[…], page, size, totalElements, totalPages, sort}`.
`GET /messages/{id}` returns `MessageDetail {id,tenantId,clientReference,recipient:{id,externalRef,maskedAddress},channel,channelUsed,category,priority,status,statusReason,terminal,template:{code,versionNo,locale},correlationId,idempotencyKey,scheduledAt,expiresAt,attemptCount,maxAttempts,nextAttemptAt,createdAt,updatedAt,attempts:[{attemptNo,channel,providerId,outcome,providerMessageId,errorCode,latencyMs,startedAt}],deliveryEvents:[…]}` — **never returns rendered body or full address**; masked addresses only (`r***@example.test`, `+1555***0101`).

## 6. Messaging/dispatch semantics
- Submission transaction: validate → idempotency claim → render-check template (variables validated, not stored) → persist `message_request` (VALIDATED) + `outbox_event` + audit in ONE transaction → return 202. `OutboxPublisher` (scheduled, 200 ms, claim batches via CAS) publishes to `MessageBus` → status QUEUED. Scheduled/future messages are not published until due.
- `DispatchEnvelope{dispatchId, messageId, tenantId, channel, priority, attemptNo, correlationId, enqueuedAt}` (no content, no PII). One `dispatchId` per (message, attemptNo).
- Consumer (`DispatchConsumer`): dedupe via `processed_message` + CAS status update QUEUED→PROCESSING (loses race ⇒ ack & skip); re-evaluate policy (cancel/expiry/suppression/quiet hours); render template at dispatch with pinned version; check provider rate limiter (token bucket; if empty ⇒ requeue with short delay without consuming an attempt); invoke provider via `ResilientProviderInvoker` (timeout + circuit breaker); record `message_attempt`; map outcome:
  - ACCEPTED → SENT_TO_PROVIDER (store providerMessageId).
  - TRANSIENT_FAILURE / RATE_LIMITED → RETRY_PENDING with `next_attempt_at = now + min(cap, base*2^(n-1)) * jitter(0.5–1.0 full-jitter variant)`; attempts exhausted ⇒ fallback channel if allowed else DEAD_LETTERED(RETRIES_EXHAUSTED).
  - PERMANENT_FAILURE → FAILED (and fallback if allowed; hard-bounce adds suppression).
  - TIMEOUT / AMBIGUOUS → DELIVERY_UNKNOWN **without blindly retrying** for channels flagged non-idempotent-safe: policy `ambiguousOutcomePolicy`: EMAIL/SMS ⇒ keep DELIVERY_UNKNOWN and wait for callback up to `reconcileAfter` then ReconciliationService queries provider `lookup(providerMessageId/clientRef)` (providers expose `Optional<ProviderStatus> lookup(String idempotencyRef)`); if provider confirms not-received ⇒ safe retry; PUSH ⇒ retry allowed (idempotent collapse key). Providers receive our `idempotencyRef = messageId:attemptGroup` so a compliant provider dedupes.
  - Poison/unprocessable envelope (missing message, deserialization failure, repeated unexpected exception ≥ `poisonThreshold`) ⇒ DLQ with reason POISON_MESSAGE; never infinite loop.
- Crash after provider acceptance before persisting: attempt row is written `STARTED` before the call; on restart `StuckMessageSweeper` finds PROCESSING with stale `locked_until` ⇒ DELIVERY_UNKNOWN (not retried blindly) ⇒ reconciliation.
- Bounded concurrency: `consumer.concurrency` (default 8) worker threads; `consumer.prefetch` (default 16, = Rabbit prefetch / in-memory queue capacity 5000 ⇒ publish blocks/rejects with 503 + `Retry-After` when API backpressure threshold reached: `messaging.backpressure.max-queue-depth`).
- Graceful shutdown: stop accepting, finish in-flight (`SmartLifecycle`, timeout 30 s), unacked go back to queue.
- Delivery semantics: at-least-once transport + idempotent consumer + provider idempotency ref = effectively-once *business* processing; never "exactly-once" across the provider boundary.

## 7. Spring Batch bulk job
`bulkSendJob` : step `readValidateStep` chunk(500) FlatFileItemReader (CSV header `externalRef,locale,var_firstName,var_amount,...`, streamed, never full load) → `RecipientRowProcessor` (validate columns, resolve recipient, dedupe within job via `(job_id, externalRef)` unique + in-chunk set) → `BulkMessageWriter` (creates VALIDATED `message_request` rows via NotificationService.submitInternal in a transaction per chunk, idempotency key `bulk-<jobId>-<externalRef>` ⇒ restart-safe). Skip policy: skip up to `skipLimit` bad rows (recorded in `bulk_job_failure`), retry transient DB errors 3× . Throttle: dispatch rate controlled by `rate_limit_per_sec` (writer-side `RateLimiter` + the normal provider limiter). Bounded concurrency: TaskExecutor `bulk.concurrency` default 4 with a partitioned/multi-threaded step only if the reader is thread-safe (`SynchronizedItemStreamReader`) . Restart: `POST /bulk-jobs/{id}/restart` relaunches same `JobParameters` ⇒ resumes from last committed chunk. Progress metrics gauge `messaging.bulk.progress`. `SyntheticDatasetGenerator` writes N-row CSV streaming to a file (`scripts/generate-dataset.sh` / `java -cp … SyntheticDatasetGenerator`).

## 8. Metrics (names are API for Grafana/alerts — keep exactly)
`messaging.messages.accepted{channel,priority,tenant}` counter; `messaging.messages.status{status}` gauge-ish via counter `messaging.transitions{from,to}`; `messaging.dispatch.delay` timer (accepted→provider submit) ; `messaging.provider.calls{provider,channel,outcome}` counter; `messaging.provider.latency{provider}` timer; `messaging.provider.circuit.state{provider}` gauge (0 closed,1 half-open,2 open); `messaging.queue.depth{priority}` gauge; `messaging.queue.inflight`; `messaging.queue.dlq.depth`; `messaging.retry.scheduled{channel}` counter; `messaging.deadletter.total{reason}` counter; `messaging.callbacks{result}` counter (accepted,duplicate,invalid_signature,replayed,out_of_order,stale); `messaging.bulk.progress{jobId}` gauge; `messaging.idempotency.hits` counter; `messaging.ratelimit.rejected{scope}`; `messaging.redis.fallback` counter; `messaging.diagnostics.write.failures`. Plus Spring/HikariCP/JVM defaults (`hikaricp_connections_*`, `http_server_requests_seconds`).

## 9. Config keys (`messaging.*` in `application.yml`, `@ConfigurationProperties`)
`messaging.dispatch.{concurrency,prefetch,max-attempts,backoff-base-ms,backoff-cap-ms,poison-threshold,receipt-timeout,reconcile-after,lock-ttl}`, `messaging.backpressure.max-queue-depth`, `messaging.bus.type=memory|rabbit`, `messaging.coordination.type=memory|redis`, `messaging.providers.<id>.{enabled,rate-per-sec,timeout-ms}`, `messaging.callback.{tolerance,secrets.<keyId>}` (dummy secrets only: `local-dev-secret-not-for-production`), `messaging.api.{rate-per-sec,burst,max-body-bytes}`, `messaging.bulk.{concurrency,chunk-size,skip-limit,max-rows,upload-dir}`, `messaging.retention.{message-days,audit-days,event-days}`, `messaging.security.dev-token-enabled`.
Profiles: `local` (H2, memory bus, memory coordination, in-memory Mongo, dev token, simulators with random faults OFF), `test` (same, deterministic), `prod-like` (PostgreSQL, Rabbit, Redis, Mongo, issuer-uri, no dev endpoints) — env-var driven, no secrets in files.

## 10. Frontend (Angular 20, `frontend/messaging-console`)
Routes (lazy, standalone): `/login` (dev token picker: choose role persona; stores JWT in memory + sessionStorage), `/dashboard`, `/messages` (list+filters+pagination), `/messages/new` (reactive form, per-channel validators, idempotency key auto-generated), `/messages/:id` (detail + lifecycle timeline), `/templates` , `/templates/:id` (versions, locale tabs, preview, submit/approve), `/preferences` (recipient search + edit with optimistic lock conflict UX), `/bulk-jobs` (+ detail with progress polling), `/providers` (health, fault injection if allowed), `/queues` (depth/retry/DLQ metrics), `/failed`, `/dead-letters`, `/delivery-events`, `/audit`, `/admin` (role-aware: pause consumers, provider config). Auth guards are UX only. HttpInterceptors: auth header, correlation id, error normalization (maps `ApiError` to actionable text), loading indicator. Dev proxy `proxy.conf.json` → `http://localhost:18080`. Unit tests (Karma/Jasmine) for services, interceptors, guards, key components. Production build output served optionally by Spring Boot static resources (`frontend` build copied to `backend/src/main/resources/static` via script, optional).

## 11. Repository layout (`<root>`)
`backend/ frontend/messaging-console/ infrastructure/{docker,compose,kubernetes,helm,rabbitmq,postgres,mongo} cloud-foundry/ observability/{prometheus,grafana,otel,alerts} tests/{end-to-end,api-http,security,failure-recovery} performance/{k6,jvm-harness,README.md} ai-engineering/ specs/ docs/{design,adr,api,architecture,database,security,operations/runbooks,leadership,interview,pdf,traceability,validation} scripts/ tools/pdf/ .github/{workflows,copilot-instructions.md,instructions/*.instructions.md,prompts/,chatmodes?} AGENTS.md .env.example .gitignore README.md Jenkinsfile bamboo-specs/`
(Root `.env.example` may be blocked by the sandbox: if so write `env.example` and note it.)

## 12. Documentation honesty rules
Every doc that mentions a validation result must cite the exact command and whether it was executed. `docs/validation/VALIDATION_REPORT.md` is generated at the end from real outputs. Anything not executed (Docker build, compose up, Helm, K8s apply, CF push, k6, Testcontainers, GitHub Actions run, Grafana render, real PostgreSQL/Rabbit/Redis/Mongo) must be listed as NOT executed.
