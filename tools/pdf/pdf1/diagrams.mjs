// Diagram specs for PDF 1 (keys are referenced from content/*.md as {{diagram:key}}).
// Every caption names the implementation file(s) the figure documents (paths relative to the repository root).
const B = 'backend/src/main/java/com/example/messaging';
const cap = (text, impl) => `${text} Implementation: ${impl}`;

const BLUE = '#2E78C7', GREEN = '#2E9D57', ORANGE = '#E08A00', PURPLE = '#8E44AD', TEAL = 'teal', RED = '#C62828', GRAY = 'gray', BROWN = 'brown';

import { ER_DIAGRAMS } from './diagrams-er.mjs';
import './diagrams-er-layout.mjs';

export const DIAGRAMS = { ...ER_DIAGRAMS };

// ---------------------------------------------------------------- Chapter 3: architecture
DIAGRAMS.layered = {
  type: 'boxes-and-arrows',
  title: 'Layered architecture',
  caption: cap('Layered view of the platform: clients and providers on the outside, a stateless Spring Boot modular monolith in the middle, and four data services below it.', `${B}/ (packages web, security, service, messaging, provider, callback), infrastructure/`),
  lanes: [
    { id: 'clients', label: 'Clients and providers', color: BLUE, nodes: [
      { id: 'sender', label: 'Service clients', sub: 'ROLE_SENDER, JWT' },
      { id: 'console', label: 'Angular console', sub: 'operators, authors' },
      { id: 'vendors', label: 'Provider webhooks', sub: 'HMAC signed' } ] },
    { id: 'edge', label: 'Edge and API', color: GREEN, nodes: [
      { id: 'filters', label: 'Servlet filters', sub: 'correlation id, size, rate limit, JWT' },
      { id: 'rest', label: 'REST controllers', sub: 'web package' },
      { id: 'cbc', label: 'CallbackController', sub: 'signature verified' } ] },
    { id: 'core', label: 'Domain services', color: ORANGE, nodes: [
      { id: 'notif', label: 'NotificationService', sub: 'submit, cancel, transition' },
      { id: 'policy', label: 'PolicyService', sub: 'consent, suppression, quiet hours' },
      { id: 'recon', label: 'ReconciliationService', sub: 'lookup of unknown outcomes' } ] },
    { id: 'async', label: 'Asynchronous pipeline', color: PURPLE, nodes: [
      { id: 'outbox', label: 'OutboxPublisher', sub: 'scheduler package', col: 1 },
      { id: 'consumer', label: 'DispatchConsumer', sub: 'bounded workers', col: 2 },
      { id: 'invoker', label: 'ResilientProviderInvoker', sub: 'timeout + circuit breaker', col: 3 } ] },
    { id: 'infra', label: 'Infrastructure', color: TEAL, nodes: [
      { id: 'db', label: 'PostgreSQL 16', sub: 'system of record' },
      { id: 'bus', label: 'RabbitMQ', sub: 'MessageBus port' },
      { id: 'redis', label: 'Redis', sub: 'fast path only' },
      { id: 'mongo', label: 'MongoDB', sub: 'diagnostics, best effort' } ] },
  ],
  edges: [
    { from: 'sender', to: 'filters', label: 'POST /messages' },
    { from: 'console', to: 'filters' },
    { from: 'vendors', to: 'cbc', label: 'receipts' },
    { from: 'filters', to: 'rest' },
    { from: 'rest', to: 'notif' },
    { from: 'cbc', to: 'notif', label: 'events' },
    { from: 'notif', to: 'policy' },
    { from: 'outbox', to: 'consumer', label: 'via bus', style: 'dashed' },
    { from: 'consumer', to: 'invoker' },
    { from: 'notif', to: 'db' },
    { from: 'outbox', to: 'bus', label: 'publish' },
    { from: 'invoker', to: 'mongo', style: 'dashed' },
    { from: 'filters', to: 'redis', style: 'dashed', label: 'rate limit' },
  ],
};

DIAGRAMS.components = {
  type: 'boxes-and-arrows',
  title: 'Backend component diagram',
  caption: cap('Backend packages grouped by responsibility. Arrows show the main compile-time dependencies; ports (MessageBus, CoordinationService, ChannelProvider) isolate the infrastructure.', `${B}/`),
  lanes: [
    { id: 'in', label: 'Inbound', color: GREEN, nodes: [
      { id: 'web', label: 'web', sub: 'controllers, DTOs, GlobalExceptionHandler' },
      { id: 'security', label: 'security', sub: 'TenantContext, AuthorizationService, filters' },
      { id: 'callback', label: 'callback', sub: 'SignatureVerifier, ReplayGuard, EventNormalizer' } ] },
    { id: 'svc', label: 'Use cases', color: ORANGE, nodes: [
      { id: 'service', label: 'service', sub: 'NotificationService, PolicyService, RetryPolicy' },
      { id: 'template', label: 'template', sub: 'TemplateRenderer, VariableValidator' },
      { id: 'batch', label: 'batch', sub: 'Spring Batch bulkSendJob' } ] },
    { id: 'pipe', label: 'Pipeline', color: PURPLE, nodes: [
      { id: 'messaging', label: 'messaging', sub: 'MessageBus, DispatchConsumer, DispatchEnvelope' },
      { id: 'scheduler', label: 'scheduler', sub: 'OutboxPublisher, StuckMessageSweeper, RetentionJob' },
      { id: 'provider', label: 'provider', sub: 'ChannelProvider, ProviderRegistry, simulators' } ] },
    { id: 'core', label: 'Core and adapters', color: TEAL, nodes: [
      { id: 'domain', label: 'domain + repository', sub: 'entities, MessageStateMachine, JPA' },
      { id: 'coord', label: 'coordination', sub: 'Redis and in-memory adapters' },
      { id: 'diag', label: 'diagnostics + metrics', sub: 'Mongo store, MessagingMetrics' } ] },
  ],
  edges: [
    { from: 'web', to: 'service' },
    { from: 'security', to: 'web', style: 'dashed' },
    { from: 'callback', to: 'service' },
    { from: 'service', to: 'template' },
    { from: 'batch', to: 'service', label: 'submitInternal' },
    { from: 'service', to: 'domain' },
    { from: 'scheduler', to: 'messaging', label: 'publish' },
    { from: 'messaging', to: 'provider' },
    { from: 'messaging', to: 'service', label: 'transition' },
    { from: 'provider', to: 'diag', style: 'dashed' },
    { from: 'service', to: 'coord', style: 'dashed' },
  ],
};

DIAGRAMS.runtime = {
  type: 'boxes-and-arrows',
  direction: 'LR',
  title: 'Runtime topology of one instance',
  caption: cap('Thread pools of one instance. Provider calls run on a separate bounded executor so a hanging provider cannot starve the consumer workers or the Tomcat threads.', `${B}/config/AsyncConfig.java, ${B}/config/PipelineProperties.java, backend/src/main/resources/application.yml`),
  lanes: [
    { id: 'a', label: 'Request path', color: GREEN, nodes: [{ id: 'tomcat', label: 'Tomcat request threads', sub: 'bounded by server config' }, { id: 'hikari', label: 'HikariCP pool', sub: 'short transactions' }] },
    { id: 'b', label: 'Schedulers', color: ORANGE, nodes: [{ id: 'sched', label: 'Scheduler threads', sub: 'outbox 200 ms, poller 1 s, sweeper 10 s' }] },
    { id: 'c', label: 'Consumers', color: PURPLE, nodes: [{ id: 'workers', label: 'Dispatch workers', sub: 'concurrency 8, prefetch 16' }, { id: 'bulk', label: 'Batch TaskExecutor', sub: 'bulk.concurrency 4' }] },
    { id: 'd', label: 'Provider calls', color: TEAL, nodes: [{ id: 'exec', label: 'Provider executor', sub: '16 threads, queue 64, time limiter' }] },
  ],
  edges: [
    { from: 'tomcat', to: 'sched', style: 'dashed', label: 'outbox rows' },
    { from: 'sched', to: 'workers', label: 'bus' },
    { from: 'workers', to: 'exec', label: 'send()' },
    { from: 'bulk', to: 'exec', style: 'dashed' },
    { from: 'tomcat', to: 'hikari' },
  ],
};

// ---------------------------------------------------------------- Chapter 4: Angular and backend
DIAGRAMS.angular = {
  type: 'boxes-and-arrows',
  title: 'Angular console architecture',
  caption: cap('Angular 20 console: lazy standalone routes, role-aware guards (UX only), interceptors and a thin API layer. The API enforces authorization; guards only hide screens.', 'frontend/messaging-console/src/app/app.routes.ts, frontend/messaging-console/src/app/core/'),
  lanes: [
    { id: 'shell', label: 'Shell and routing', color: BLUE, nodes: [
      { id: 'routes', label: 'app.routes.ts', sub: 'lazy loadComponent' },
      { id: 'guards', label: 'authGuard / roleGuard', sub: 'core/auth' },
      { id: 'layout', label: 'ShellComponent', sub: 'layout/shell.ts' } ] },
    { id: 'features', label: 'Feature pages (features/)', color: GREEN, nodes: [
      { id: 'msgs', label: 'messages', sub: 'list, submit, detail' },
      { id: 'tpl', label: 'templates', sub: 'versions, preview, approve' },
      { id: 'ops', label: 'queues, failed, dead-letters', sub: 'operator views' },
      { id: 'bulkp', label: 'bulk-jobs, providers, audit', sub: 'admin views' } ] },
    { id: 'core', label: 'Core (core/)', color: ORANGE, nodes: [
      { id: 'api', label: 'core/api services', sub: 'typed HttpClient wrappers' },
      { id: 'http', label: 'core/http interceptors', sub: 'auth, correlation id, errors' },
      { id: 'models', label: 'core/models', sub: 'api.models.ts' } ] },
    { id: 'be', label: 'Backend', color: PURPLE, nodes: [{ id: 'proxy', label: 'proxy.conf.json', sub: '/api -> :18080' }, { id: 'rest', label: 'REST API', sub: '/api/v1' }] },
  ],
  edges: [
    { from: 'routes', to: 'guards' }, { from: 'guards', to: 'layout' },
    { from: 'layout', to: 'msgs' }, { from: 'layout', to: 'tpl' }, { from: 'layout', to: 'ops' },
    { from: 'msgs', to: 'api' }, { from: 'tpl', to: 'api' }, { from: 'ops', to: 'api' }, { from: 'bulkp', to: 'api' },
    { from: 'api', to: 'http' }, { from: 'http', to: 'proxy' }, { from: 'proxy', to: 'rest' },
  ],
};

DIAGRAMS.requestChain = {
  type: 'sequence',
  autonumber: true,
  caption: cap('Request handling chain for an authenticated API call: filters first, then Spring Security, controller, service, repository.', `${B}/common/CorrelationIdFilter.java, ${B}/security/RequestSizeFilter.java, ${B}/security/RateLimitFilter.java, ${B}/config/SecurityConfig.java`),
  participants: [{ id: 'c', label: 'Client' }, { id: 'f', label: 'Filters' }, { id: 's', label: 'Spring Security' }, { id: 'ctl', label: 'Controller' }, { id: 'svc', label: 'Service' }, { id: 'db', label: 'Database' }],
  steps: [
    { from: 'c', to: 'f', text: 'HTTP request + Bearer JWT' },
    { from: 'f', to: 'f', text: 'CorrelationIdFilter: accept or generate id' },
    { from: 'f', to: 'f', text: 'RequestSizeFilter: > 256 KB => 413' },
    { from: 'f', to: 's', text: 'JWT RS256 validation' },
    { note: '401 UNAUTHENTICATED on a missing or invalid token', over: ['f', 's'] },
    { from: 's', to: 'f', text: 'TenantContext + roles from claims', kind: 'return' },
    { from: 'f', to: 'f', text: 'RateLimitFilter: 50 req/s, burst 100 => 429' },
    { from: 'f', to: 'ctl', text: 'dispatch to controller' },
    { from: 'ctl', to: 'svc', text: 'DTO record + AuthorizationService checks' },
    { from: 'svc', to: 'db', text: 'tenant-scoped queries' },
    { from: 'db', to: 'svc', text: 'rows', kind: 'return' },
    { from: 'svc', to: 'ctl', text: 'result', kind: 'return' },
    { from: 'ctl', to: 'c', text: 'JSON + X-Correlation-Id', kind: 'return' },
  ],
};

// ---------------------------------------------------------------- Chapter 5: submission
DIAGRAMS.submission = {
  type: 'sequence',
  autonumber: true,
  caption: cap('Notification submission: one database transaction writes the message, the outbox row, the audit event and the idempotency response; the provider is never called on the request thread.', `${B}/web/MessageController.java, ${B}/service/NotificationService.java, ${B}/service/IdempotencyService.java`),
  participants: [{ id: 'cl', label: 'Client' }, { id: 'ctl', label: 'MessageController' }, { id: 'idem', label: 'IdempotencyService' }, { id: 'ns', label: 'NotificationService' }, { id: 'pol', label: 'PolicyService' }, { id: 'db', label: 'Database' }],
  steps: [
    { from: 'cl', to: 'ctl', text: 'POST /api/v1/messages (Idempotency-Key)' },
    { from: 'ctl', to: 'ctl', text: 'BackpressureGuard.check()' },
    { alt: 'queue depth at max-queue-depth', steps: [{ from: 'ctl', to: 'cl', text: '503 SERVICE_UNAVAILABLE + Retry-After', kind: 'return' }] },
    { from: 'ctl', to: 'ns', text: 'submit(SubmitMessageRequest)' },
    { from: 'ns', to: 'idem', text: 'claim(tenant, key, request hash)' },
    { alt: 'same key, same hash, COMPLETED', steps: [{ from: 'idem', to: 'cl', text: '200 original body + Idempotent-Replay: true', kind: 'return' }], else: { label: 'same key, different hash', steps: [{ from: 'idem', to: 'cl', text: '422 IDEMPOTENCY_KEY_REUSED', kind: 'return' }] } },
    { from: 'ns', to: 'ns', text: 'resolve recipient in tenant (404 if foreign)' },
    { from: 'ns', to: 'pol', text: 'evaluate(channel, category, priority, recipient)' },
    { from: 'ns', to: 'ns', text: 'pin template version, validate variables' },
    { note: 'ONE transaction', over: ['ns', 'db'] },
    { from: 'ns', to: 'db', text: 'message_request ACCEPTED -> VALIDATED (or REJECTED)' },
    { from: 'ns', to: 'db', text: 'outbox_event NEW, audit_event, idempotency complete' },
    { from: 'db', to: 'ns', text: 'commit', kind: 'return' },
    { from: 'ns', to: 'cl', text: '202 MessageAcceptedResponse + Location', kind: 'return' },
  ],
};

DIAGRAMS.idempotencyLayers = {
  type: 'boxes-and-arrows',
  direction: 'LR',
  title: 'Four layers of idempotency (ADR-0004)',
  caption: cap('Duplicates are neutralised at four independent layers so that at-least-once transport yields one business effect.', `${B}/service/IdempotencyService.java, ${B}/messaging/DispatchConsumer.java, ${B}/callback/ReplayGuard.java, ${B}/provider/ProviderRequest.java`),
  lanes: [
    { id: 'l1', label: '1. API', color: BLUE, nodes: [{ id: 'n1', label: 'Idempotency-Key', sub: 'UNIQUE(tenant_id, idempotency_key)' }] },
    { id: 'l2', label: '2. Consumer', color: GREEN, nodes: [{ id: 'n2', label: 'processed_message', sub: 'dispatch_id unique + CAS claim' }] },
    { id: 'l3', label: '3. Callback', color: ORANGE, nodes: [{ id: 'n3', label: 'ReplayGuard + unique event', sub: 'UNIQUE(provider_id, provider_event_id)' }] },
    { id: 'l4', label: '4. Provider', color: PURPLE, nodes: [{ id: 'n4', label: 'idempotencyRef', sub: 'messageId:attemptGroup' }] },
  ],
  edges: [{ from: 'n1', to: 'n2', label: 'one message' }, { from: 'n2', to: 'n4', label: 'one send' }, { from: 'n3', to: 'n4', style: 'dashed', label: 'one receipt effect' }],
};

DIAGRAMS.policyChain = {
  type: 'boxes-and-arrows',
  direction: 'LR',
  title: 'Policy evaluation order',
  caption: cap('Ordered policy checks run at accept time and again at dispatch time; a fallback channel is evaluated through the same chain, so fallback never bypasses consent.', `${B}/service/PolicyService.java, ${B}/service/PolicyDecision.java, ${B}/service/PolicyReason.java`),
  lanes: [
    { id: 'p1', label: 'Step 1', color: BLUE, nodes: [{ id: 'r1', label: 'Recipient exists, not deleted' }] },
    { id: 'p2', label: 'Step 2', color: RED, nodes: [{ id: 'r2', label: 'Global suppression', sub: 'all categories blocked' }] },
    { id: 'p3', label: 'Step 3', color: ORANGE, nodes: [{ id: 'r3', label: 'Category and opt-in', sub: 'MARKETING needs consent' }] },
    { id: 'p4', label: 'Step 4', color: PURPLE, nodes: [{ id: 'r4', label: 'Quiet hours', sub: 'defer, do not reject' }] },
    { id: 'p5', label: 'Step 5', color: GREEN, nodes: [{ id: 'r5', label: 'Fallback channel', sub: 'same chain again' }] },
  ],
  edges: [{ from: 'r1', to: 'r2' }, { from: 'r2', to: 'r3' }, { from: 'r3', to: 'r4' }, { from: 'r4', to: 'r5' }],
};

// ---------------------------------------------------------------- Chapter 6: lifecycle
DIAGRAMS.lifecycle = {
  type: 'state',
  caption: cap('Message lifecycle state machine: the exact transition table of MessageStateMachine (12 statuses). SCHEDULED is not a status: scheduled messages are VALIDATED with a future scheduled_at.', `${B}/domain/MessageStateMachine.java, ${B}/domain/MessageStatus.java`),
  states: [
    { id: 'ACCEPTED', initial: true, pos: [1, 0] }, { id: 'VALIDATED', pos: [1, 1] }, { id: 'QUEUED', pos: [1, 2] }, { id: 'PROCESSING', pos: [1, 3] },
    { id: 'SENT_TO_PROVIDER', pos: [2, 4] }, { id: 'DELIVERED', kind: 'ok', pos: [3, 5] }, { id: 'RETRY_PENDING', kind: 'warn', pos: [0, 4] },
    { id: 'FAILED', kind: 'error', pos: [1, 5] }, { id: 'DEAD_LETTERED', kind: 'error', pos: [0, 5] }, { id: 'REJECTED', kind: 'error', pos: [3, 1] },
    { id: 'CANCELLED', kind: 'neutral', pos: [3, 3] }, { id: 'DELIVERY_UNKNOWN', label: 'DELIVERY_\nUNKNOWN', kind: 'warn', pos: [2, 5] },
  ],
  transitions: [
    { from: 'ACCEPTED', to: 'VALIDATED' }, { from: 'ACCEPTED', to: 'REJECTED' },
    { from: 'VALIDATED', to: 'QUEUED', label: 'outbox' }, { from: 'VALIDATED', to: 'CANCELLED' }, { from: 'VALIDATED', to: 'REJECTED' },
    { from: 'QUEUED', to: 'PROCESSING', label: 'claim' }, { from: 'QUEUED', to: 'CANCELLED' },
    { from: 'PROCESSING', to: 'SENT_TO_PROVIDER' }, { from: 'PROCESSING', to: 'RETRY_PENDING' }, { from: 'PROCESSING', to: 'FAILED' },
    { from: 'PROCESSING', to: 'DELIVERY_UNKNOWN' }, { from: 'PROCESSING', to: 'REJECTED' },
    { from: 'RETRY_PENDING', to: 'QUEUED', label: 'backoff' }, { from: 'RETRY_PENDING', to: 'DEAD_LETTERED' }, { from: 'RETRY_PENDING', to: 'CANCELLED' },
    { from: 'SENT_TO_PROVIDER', to: 'DELIVERED' }, { from: 'SENT_TO_PROVIDER', to: 'FAILED' }, { from: 'SENT_TO_PROVIDER', to: 'DELIVERY_UNKNOWN' },
    { from: 'DELIVERY_UNKNOWN', to: 'DELIVERED' }, { from: 'DELIVERY_UNKNOWN', to: 'FAILED' },
    { from: 'FAILED', to: 'DEAD_LETTERED' }, { from: 'FAILED', to: 'QUEUED', label: 'operator' }, { from: 'DEAD_LETTERED', to: 'QUEUED', label: 'replay' },
  ],
};

DIAGRAMS.cancelRace = {
  type: 'sequence',
  autonumber: true,
  caption: cap('Cancel versus dispatch: both actors use a compare-and-set on the status column, so exactly one wins and the loser backs off without side effects.', `${B}/service/NotificationService.java, ${B}/messaging/DispatchConsumer.java, backend/src/test/java/com/example/messaging/pipeline/CancelRaceIT.java`),
  participants: [{ id: 'op', label: 'API cancel' }, { id: 'db', label: 'message_request' }, { id: 'dc', label: 'DispatchConsumer' }],
  steps: [
    { from: 'op', to: 'db', text: 'UPDATE status=CANCELLED WHERE status IN (VALIDATED, QUEUED, RETRY_PENDING)' },
    { from: 'dc', to: 'db', text: 'UPDATE status=PROCESSING WHERE status=QUEUED' },
    { alt: 'cancel committed first', steps: [{ from: 'db', to: 'dc', text: '0 rows: consumer acks and skips', kind: 'return' }, { from: 'db', to: 'op', text: '200 CANCELLED', kind: 'return' }], else: { label: 'claim committed first', steps: [{ from: 'db', to: 'op', text: '0 rows: 409 ILLEGAL_STATE_TRANSITION', kind: 'return' }, { from: 'db', to: 'dc', text: '1 row: dispatch continues', kind: 'return' }] } },
  ],
};

// ---------------------------------------------------------------- Chapter 7: queue dispatch
DIAGRAMS.outbox = {
  type: 'sequence',
  autonumber: true,
  caption: cap('Transactional outbox: the publisher claims NEW rows, moves VALIDATED to QUEUED and publishes inside one transaction; a failed publish rolls everything back, which gives at-least-once publication.', `${B}/scheduler/OutboxPublisher.java, ${B}/messaging/OutboxWriter.java, ${B}/messaging/BackpressureGuard.java`),
  participants: [{ id: 'ob', label: 'OutboxPublisher' }, { id: 'db', label: 'Database' }, { id: 'bus', label: 'MessageBus' }, { id: 'dc', label: 'DispatchConsumer' }],
  steps: [
    { from: 'ob', to: 'db', text: 'select outbox NEW AND available_at <= now (batch 100)' },
    { from: 'ob', to: 'db', text: 'markSent(row) CAS' },
    { from: 'ob', to: 'db', text: 'CAS VALIDATED -> QUEUED (never fails if already QUEUED)' },
    { from: 'ob', to: 'bus', text: 'publish(DispatchEnvelope)' },
    { alt: 'publish fails (backpressure, broker down)', steps: [{ from: 'ob', to: 'db', text: 'rollback: row stays NEW, retried in 200 ms', kind: 'return' }], else: { label: 'publish ok', steps: [{ from: 'ob', to: 'db', text: 'commit', kind: 'return' }] } },
    { from: 'bus', to: 'dc', text: 'deliver (prefetch bounded)' },
    { note: 'An envelope can overtake the commit: the consumer requeues a still-VALIDATED message for 150 ms', over: ['bus', 'dc'] },
  ],
};

DIAGRAMS.dispatch = {
  type: 'sequence',
  autonumber: true,
  caption: cap('Dispatch with retry: pre-claim checks, one claim transaction, provider call under a circuit breaker, then an outcome-specific transition.', `${B}/messaging/DispatchConsumer.java, ${B}/provider/ResilientProviderInvoker.java, ${B}/service/RetryPolicy.java`),
  participants: [{ id: 'bus', label: 'MessageBus' }, { id: 'dc', label: 'DispatchConsumer' }, { id: 'rl', label: 'Rate limiter' }, { id: 'inv', label: 'ResilientProviderInvoker' }, { id: 'prv', label: 'Provider' }, { id: 'db', label: 'Database' }],
  steps: [
    { from: 'bus', to: 'dc', text: 'deliver envelope' },
    { from: 'dc', to: 'db', text: 'pre-checks: cancel, expiry, policy, provider, circuit' },
    { from: 'dc', to: 'rl', text: 'tryAcquire(provider token)' },
    { alt: 'no token or breaker open', steps: [{ from: 'dc', to: 'bus', text: 'requeue 0.1 - 5 s, no attempt consumed', kind: 'async' }], else: { label: 'token acquired', steps: [
      { from: 'dc', to: 'db', text: 'ONE tx: processed_message + CAS QUEUED->PROCESSING + attempt STARTED' },
      { from: 'dc', to: 'inv', text: 'send(idempotencyRef = messageId:attemptGroup)' },
      { from: 'inv', to: 'prv', text: 'call with time limiter' },
      { from: 'prv', to: 'inv', text: 'ProviderResult(outcome)', kind: 'return' },
    ] } },
    { alt: 'ACCEPTED', steps: [{ from: 'dc', to: 'db', text: 'SENT_TO_PROVIDER + providerMessageId' }], else: { label: 'TRANSIENT or RATE_LIMITED', steps: [{ from: 'dc', to: 'db', text: 'RETRY_PENDING, next_attempt_at = backoff x jitter' }, { from: 'dc', to: 'bus', text: 'publishDelayed(new dispatchId)', kind: 'async' }] } },
    { alt: 'PERMANENT_FAILURE', steps: [{ from: 'dc', to: 'db', text: 'FAILED (+ fallback channel, hard bounce suppresses)' }], else: { label: 'TIMEOUT or AMBIGUOUS', steps: [{ from: 'dc', to: 'db', text: 'DELIVERY_UNKNOWN (no blind retry for EMAIL/SMS)' }] } },
  ],
};

DIAGRAMS.consumerDecision = {
  type: 'boxes-and-arrows',
  title: 'Outcome mapping',
  caption: cap('How each ProviderOutcome maps to a lifecycle transition (CONTRACT 6, ADR-0006, ADR-0010).', `${B}/provider/ProviderOutcome.java, ${B}/messaging/DispatchConsumer.java`),
  lanes: [
    { id: 'o', label: 'ProviderOutcome', color: BLUE, nodes: [
      { id: 'acc', label: 'ACCEPTED' }, { id: 'tr', label: 'TRANSIENT_FAILURE / RATE_LIMITED' }, { id: 'pf', label: 'PERMANENT_FAILURE' }, { id: 'to', label: 'TIMEOUT / AMBIGUOUS' } ] },
    { id: 's', label: 'Status', color: ORANGE, nodes: [
      { id: 'stp', label: 'SENT_TO_PROVIDER' }, { id: 'rp', label: 'RETRY_PENDING' }, { id: 'fl', label: 'FAILED' }, { id: 'du', label: 'DELIVERY_UNKNOWN' } ] },
    { id: 'n', label: 'Follow-up', color: GREEN, nodes: [
      { id: 'cb', label: 'Receipt callback' }, { id: 'bo', label: 'Backoff, then QUEUED or DEAD_LETTERED' }, { id: 'fb', label: 'Fallback channel or operator' }, { id: 'rc', label: 'Callback or lookup reconciliation' } ] },
  ],
  edges: [
    { from: 'acc', to: 'stp' }, { from: 'tr', to: 'rp' }, { from: 'pf', to: 'fl' }, { from: 'to', to: 'du' },
    { from: 'stp', to: 'cb' }, { from: 'rp', to: 'bo' }, { from: 'fl', to: 'fb' }, { from: 'du', to: 'rc' },
  ],
};

DIAGRAMS.busTopology = {
  type: 'boxes-and-arrows',
  direction: 'LR',
  title: 'RabbitMQ topology (prod profile)',
  caption: cap('Priority dispatch queue with TTL retry tiers and dead-letter exchange. Rabbit code is NOT executed in this repository: only the pure parts and the topology versus definitions.json are tested.', `infrastructure/rabbitmq/definitions.json, ${B}/messaging/RabbitTopology.java, ${B}/messaging/RabbitMessageBus.java`),
  lanes: [
    { id: 'pub', label: 'Publish', color: BLUE, nodes: [{ id: 'op', label: 'OutboxPublisher' }, { id: 'pd', label: 'publishDelayed', sub: 'retry, rate-limit requeue' }] },
    { id: 'ex', label: 'Exchanges', color: ORANGE, nodes: [{ id: 'exd', label: 'dispatch exchange' }, { id: 'exdlx', label: 'dead-letter exchange' }] },
    { id: 'q', label: 'Queues', color: PURPLE, nodes: [{ id: 'qd', label: 'dispatch queue', sub: 'x-max-priority 9' }, { id: 'qr', label: 'TTL delay tiers', sub: '5 s, 30 s, 2 m, 10 m' }, { id: 'qdl', label: 'dead-letter queue' }] },
    { id: 'c', label: 'Consume', color: GREEN, nodes: [{ id: 'dc', label: 'DispatchConsumer', sub: 'manual ack, prefetch 16' }] },
  ],
  edges: [
    { from: 'op', to: 'exd' }, { from: 'pd', to: 'qr' }, { from: 'exd', to: 'qd' }, { from: 'qr', to: 'qd', label: 'TTL expiry', style: 'dashed' },
    { from: 'qd', to: 'dc' }, { from: 'dc', to: 'exdlx', label: 'poison', color: RED }, { from: 'exdlx', to: 'qdl' },
  ],
};

// ---------------------------------------------------------------- Chapter 8: providers
DIAGRAMS.providers = {
  type: 'boxes-and-arrows',
  title: 'Provider adapter architecture',
  caption: cap('Ports and adapters for providers: orchestration only knows ChannelProvider and ProviderOutcome. A new vendor is a new bean plus configuration (ADR-0002, spec 0002).', `${B}/provider/ChannelProvider.java, ${B}/provider/ProviderRegistry.java, ${B}/provider/simulator/, specs/0002-new-provider-adapter.md`),
  lanes: [
    { id: 'orch', label: 'Orchestration', color: BLUE, nodes: [{ id: 'dc', label: 'DispatchConsumer' }, { id: 'rs', label: 'Reconciliation lookup' }] },
    { id: 'res', label: 'Resilience', color: ORANGE, nodes: [{ id: 'reg', label: 'ProviderRegistry', sub: 'strategy routing by priority_order' }, { id: 'inv', label: 'ResilientProviderInvoker', sub: 'permit, time limiter, breaker' }] },
    { id: 'port', label: 'Ports', color: PURPLE, nodes: [{ id: 'cp', label: 'ChannelProvider', sub: 'send, lookup, providerId, channel' }, { id: 'sub', label: 'Channel sub-interfaces', sub: 'Email, Sms, PushNotification' }] },
    { id: 'ad', label: 'Adapters (simulated)', color: TEAL, nodes: [{ id: 'se', label: 'Email simulator', sub: 'SimulatedEmail' }, { id: 'ss', label: 'SMS simulator', sub: 'SimulatedSms' }, { id: 'sp', label: 'Push simulator', sub: 'SimulatedPush' }, { id: 'ac', label: 'Acme SMS', sub: 'new-provider example' }] },
  ],
  edges: [
    { from: 'dc', to: 'reg' }, { from: 'rs', to: 'reg', style: 'dashed' }, { from: 'reg', to: 'inv' }, { from: 'inv', to: 'cp' },
    { from: 'cp', to: 'sub' }, { from: 'sub', to: 'se' }, { from: 'sub', to: 'ss' }, { from: 'sub', to: 'sp' }, { from: 'sub', to: 'ac' },
  ],
};

DIAGRAMS.circuit = {
  type: 'state',
  layout: 'circle',
  caption: cap('Circuit breaker around each provider (Resilience4j). Defaults: 50 percent failure rate over a 20-call window, minimum 10 calls, 5 s open wait, 3 half-open probes. The permit is reserved before the claim so a refusing breaker never burns an attempt.', `${B}/provider/ResilientProviderInvoker.java, ${B}/config/PipelineProperties.java, backend/src/main/resources/application.yml`),
  states: [{ id: 'CLOSED', initial: true, kind: 'ok' }, { id: 'OPEN', kind: 'error' }, { id: 'HALF_OPEN', kind: 'warn' }],
  transitions: [
    { from: 'CLOSED', to: 'OPEN', label: 'failure rate >= 50%' },
    { from: 'OPEN', to: 'HALF_OPEN', label: 'open-state-wait 5 s (automatic)' },
    { from: 'HALF_OPEN', to: 'CLOSED', label: '3 probes succeed' },
    { from: 'HALF_OPEN', to: 'OPEN', label: 'probe fails' },
  ],
};

// ---------------------------------------------------------------- Chapter 9: channels
DIAGRAMS.channels = {
  type: 'boxes-and-arrows',
  direction: 'LR',
  title: 'Channel workflows side by side',
  caption: cap('Email, SMS and push share the pipeline but differ in what the platform does after an ambiguous result and in what a receipt means.', `${B}/provider/EmailProvider.java, ${B}/provider/SmsProvider.java, ${B}/provider/PushNotificationProvider.java, ${B}/callback/SimulatedEmailPayloadMapper.java`),
  lanes: [
    { id: 'email', label: 'EMAIL', color: BLUE, nodes: [{ id: 'e1', label: 'Subject + HTML body', sub: 'HTML-escaped variables' }, { id: 'e2', label: 'Ambiguous: wait, lookup', sub: 'no blind resend' }, { id: 'e3', label: 'hard_bounce: suppress', sub: 'OPENED, CLICKED recorded' }] },
    { id: 'sms', label: 'SMS', color: GREEN, nodes: [{ id: 's1', label: 'Plain text, E.164 number', sub: 'subject must be empty' }, { id: 's2', label: 'Ambiguous: wait, lookup', sub: 'duplicate cost' }, { id: 's3', label: 'DELIVRD, UNDELIV', sub: 'carrier receipts may be late' }] },
    { id: 'push', label: 'PUSH', color: ORANGE, nodes: [{ id: 'p1', label: 'Title + body, device token', sub: 'collapseKey, ttlSeconds' }, { id: 'p2', label: 'Ambiguous: retry', sub: 'provider dedupes on idempotency ref' }, { id: 'p3', label: 'token_invalid: suppress token', sub: 'FAILED TOKEN_INVALID' }] },
  ],
  edges: [{ from: 'e1', to: 'e2' }, { from: 'e2', to: 'e3' }, { from: 's1', to: 's2' }, { from: 's2', to: 's3' }, { from: 'p1', to: 'p2' }, { from: 'p2', to: 'p3' }],
};

// ---------------------------------------------------------------- Chapter 10: templates
DIAGRAMS.templateStates = {
  type: 'state',
  caption: cap('Template version lifecycle with four-eyes approval. Published versions are immutable; edits create a new version.', `${B}/domain/TemplateStatus.java, ${B}/service/TemplateService.java`),
  states: [{ id: 'DRAFT', initial: true }, { id: 'PENDING_APPROVAL', kind: 'warn' }, { id: 'PUBLISHED', kind: 'ok' }, { id: 'RETIRED', kind: 'neutral' }],
  transitions: [
    { from: 'DRAFT', to: 'PENDING_APPROVAL', label: 'submit (author)' },
    { from: 'PENDING_APPROVAL', to: 'PUBLISHED', label: 'approve (approver != author)' },
    { from: 'PENDING_APPROVAL', to: 'DRAFT', label: 'reject with comment' },
    { from: 'PUBLISHED', to: 'RETIRED', label: 'retire or superseded' },
  ],
};

DIAGRAMS.templateFlow = {
  type: 'sequence',
  autonumber: true,
  caption: cap('Template approval flow: author drafts, submits; a different approver publishes; the previous published version of the same locale is retired in the same step.', `${B}/web/TemplateController.java, ${B}/service/TemplateService.java, ${B}/security/AuthorizationService.java`),
  participants: [{ id: 'au', label: 'Author' }, { id: 'api', label: 'TemplateController' }, { id: 'ts', label: 'TemplateService' }, { id: 'db', label: 'Database' }, { id: 'ap', label: 'Approver' }],
  steps: [
    { from: 'au', to: 'api', text: 'POST /templates (code, channel, category, body)' },
    { from: 'api', to: 'ts', text: 'create' },
    { from: 'ts', to: 'db', text: 'template + template_version DRAFT + audit' },
    { from: 'au', to: 'api', text: 'PUT .../versions/1?locale=en (edit DRAFT only)' },
    { note: 'Stale version field => 409 CONFLICT_OPTIMISTIC_LOCK; created_by becomes the editor', over: ['api', 'ts'] },
    { from: 'au', to: 'api', text: 'POST .../submit' },
    { from: 'ts', to: 'db', text: 'DRAFT -> PENDING_APPROVAL + audit' },
    { from: 'ap', to: 'api', text: 'POST .../approve' },
    { from: 'api', to: 'ts', text: 'role TEMPLATE_APPROVER, tenant owns template, approver != created_by' },
    { from: 'ts', to: 'db', text: 'PENDING_APPROVAL -> PUBLISHED; previous PUBLISHED -> RETIRED; audit TEMPLATE_PUBLISHED' },
  ],
};

// ---------------------------------------------------------------- Chapter 12: callbacks and reconciliation
DIAGRAMS.callback = {
  type: 'sequence',
  autonumber: true,
  caption: cap('Delivery callback as implemented (see DEVIATIONS 7): signature first, then replay guard, normalisation, then a compare-and-set transition ranked by the monotonic rule.', `${B}/callback/CallbackController.java, ${B}/callback/CallbackService.java, ${B}/callback/SignatureVerifier.java, ${B}/callback/DeliveryEventProcessor.java`),
  participants: [{ id: 'p', label: 'Provider' }, { id: 'cc', label: 'CallbackController' }, { id: 'sv', label: 'SignatureVerifier' }, { id: 'rg', label: 'ReplayGuard' }, { id: 'en', label: 'EventNormalizer' }, { id: 'dp', label: 'DeliveryEventProcessor' }, { id: 'db', label: 'Database' }],
  steps: [
    { from: 'p', to: 'cc', text: 'POST /callbacks/{providerId} + X-Signature, X-Provider-Key-Id, X-Event-Id' },
    { from: 'cc', to: 'sv', text: 'HMAC-SHA256(t + "." + rawBody), tolerance 5 min' },
    { alt: 'any signature problem, unknown provider, unbound key', steps: [{ from: 'sv', to: 'p', text: '401 INVALID_SIGNATURE (identical for all)', kind: 'return' }] },
    { from: 'cc', to: 'rg', text: 'seen(providerId:eventId)?' },
    { alt: 'already processed', steps: [{ from: 'rg', to: 'p', text: '200 {"result":"replayed"}', kind: 'return' }] },
    { from: 'cc', to: 'en', text: 'normalize(providerId, rawBody)' },
    { alt: 'malformed or unknown vendor status', steps: [{ from: 'en', to: 'p', text: '400 MALFORMED_REQUEST', kind: 'return' }] },
    { from: 'cc', to: 'dp', text: 'apply(NormalizedEvent)' },
    { from: 'dp', to: 'db', text: 'insert delivery_event (unique provider_id, provider_event_id)' },
    { from: 'dp', to: 'db', text: 'resolveReceipt + CAS transition + audit (same transaction)' },
    { from: 'dp', to: 'p', text: '202 {"result": accepted | duplicate | out_of_order | stale | conflict | unknown_message}', kind: 'return' },
  ],
};

DIAGRAMS.receiptRank = {
  type: 'boxes-and-arrows',
  direction: 'LR',
  title: 'Receipt ranking',
  caption: cap('Monotonic rank rule: a lower rank never overwrites a higher rank; the first terminal receipt wins and a contradicting terminal receipt is audited as DELIVERY_EVENT_CONFLICT.', `${B}/domain/MessageStateMachine.java (resolveReceipt), ${B}/domain/DeliveryEventType.java`),
  lanes: [
    { id: 'r1', label: 'Rank 1', color: BLUE, nodes: [{ id: 'a', label: 'PROVIDER_ACCEPTED, SENT, DEFERRED', sub: 'ignored once outcome known' }] },
    { id: 'r3', label: 'Rank 3 (terminal)', color: GREEN, nodes: [{ id: 'd', label: 'DELIVERED', sub: 'applies from PROCESSING, SENT_TO_PROVIDER, DELIVERY_UNKNOWN' }, { id: 'f', label: 'BOUNCED, FAILED, REJECTED', sub: 'to FAILED' }] },
    { id: 'eng', label: 'Engagement', color: ORANGE, nodes: [{ id: 'o', label: 'OPENED, CLICKED, UNSUBSCRIBED', sub: 'recorded, unsubscribe clears opt-in' }] },
    { id: 'cf', label: 'Conflict', color: RED, nodes: [{ id: 'c', label: 'DELIVERED then FAILED (or reverse)', sub: 'first terminal kept, audit' }] },
  ],
  edges: [{ from: 'a', to: 'd' }, { from: 'a', to: 'f' }, { from: 'd', to: 'c', style: 'dashed' }, { from: 'f', to: 'c', style: 'dashed' }],
};

DIAGRAMS.reconcile = {
  type: 'sequence',
  autonumber: true,
  caption: cap('Ambiguous outcome reconciliation: unknown is a state. The sweeper and the reconciliation service resolve it from provider evidence only, never by guessing.', `${B}/scheduler/StuckMessageSweeper.java, ${B}/service/ReconciliationService.java, ${B}/provider/ChannelProvider.java (lookup)`),
  participants: [{ id: 'dc', label: 'DispatchConsumer' }, { id: 'db', label: 'Database' }, { id: 'sw', label: 'StuckMessageSweeper' }, { id: 'rs', label: 'ReconciliationService' }, { id: 'pr', label: 'Provider.lookup' }],
  steps: [
    { from: 'dc', to: 'db', text: 'attempt STARTED, PROCESSING, locked_until = now + 2 min' },
    { note: 'timeout, AMBIGUOUS result, or crash happens here', over: ['dc', 'db'] },
    { alt: 'timeout or AMBIGUOUS', steps: [{ from: 'dc', to: 'db', text: 'PROCESSING -> DELIVERY_UNKNOWN' }], else: { label: 'worker crashed', steps: [{ from: 'sw', to: 'db', text: 'PROCESSING with locked_until < now -> DELIVERY_UNKNOWN (STUCK_SWEEP audit)' }] } },
    { from: 'sw', to: 'db', text: 'SENT_TO_PROVIDER without receipt after 15 min -> DELIVERY_UNKNOWN' },
    { loop: 'DELIVERY_UNKNOWN older than reconcile-after (5 min), max 5 checks', steps: [
      { from: 'rs', to: 'pr', text: 'lookup(idempotencyRef)' },
      { alt: 'delivered', steps: [{ from: 'rs', to: 'db', text: '-> DELIVERED (recon: evidence event)' }], else: { label: 'not received', steps: [{ from: 'rs', to: 'db', text: '-> FAILED -> QUEUED, same idempotency reference group' }] } },
      { from: 'rs', to: 'db', text: 'accepted only or lookup error: stay unknown, audit RECONCILIATION_CHECK' },
    ] },
  ],
};

// ---------------------------------------------------------------- Chapter 13: retry and DLQ
DIAGRAMS.backoff = {
  type: 'bar',
  title: 'Backoff ceilings with production defaults',
  unit: 'seconds',
  caption: cap('Un-jittered backoff ceiling min(cap, base x 2^(n-1)) for base 1000 ms and cap 60000 ms; the actual wait is the ceiling times a random factor in [0.5, 1.0]. Computed from the formula in RetryPolicy, not measured.', `${B}/service/RetryPolicy.java, backend/src/main/resources/application.yml`),
  data: [1, 2, 3, 4, 5, 6, 7].map((n) => ({ label: `retry ${n}`, value: Math.min(60, 1 * 2 ** (n - 1)) })),
};

DIAGRAMS.dlqFlow = {
  type: 'boxes-and-arrows',
  title: 'Retry, fallback and dead-letter paths',
  caption: cap('Where a message goes when delivery does not succeed. Replay and requeue are operator-only and audited; a replayed message receives a fresh attempt budget.', `${B}/service/RetryPolicy.java, ${B}/messaging/DispatchConsumer.java, ${B}/service/ReplayService.java, ${B}/service/DefaultDeadLetterOperations.java`),
  lanes: [
    { id: 'a', label: 'Failure', color: RED, nodes: [{ id: 'tf', label: 'Transient or rate limited' }, { id: 'ex', label: 'Retries exhausted' }, { id: 'pm', label: 'Poison envelope' }, { id: 'pf', label: 'Permanent failure' }] },
    { id: 'b', label: 'Automatic handling', color: ORANGE, nodes: [{ id: 'rp', label: 'RETRY_PENDING', sub: 'backoff with jitter' }, { id: 'fb', label: 'Fallback channel', sub: 'only if consent allows' }, { id: 'fl', label: 'FAILED' }] },
    { id: 'c', label: 'Parking', color: PURPLE, nodes: [{ id: 'dl', label: 'DEAD_LETTERED', sub: 'RETRIES_EXHAUSTED, POISON_MESSAGE' }] },
    { id: 'd', label: 'Operator', color: GREEN, nodes: [{ id: 'rq', label: 'requeue / replay', sub: 'fresh attempt budget' }, { id: 'ds', label: 'discard', sub: 'audited, flag only' }] },
  ],
  edges: [
    { from: 'tf', to: 'rp' }, { from: 'ex', to: 'fb' }, { from: 'ex', to: 'dl' }, { from: 'pm', to: 'dl' }, { from: 'pf', to: 'fl' },
    { from: 'fl', to: 'fb', style: 'dashed' }, { from: 'fl', to: 'rq', label: 'requeue' }, { from: 'dl', to: 'rq', label: 'replay' }, { from: 'dl', to: 'ds' },
  ],
};

// ---------------------------------------------------------------- Chapter 14: bulk
DIAGRAMS.bulk = {
  type: 'sequence',
  autonumber: true,
  caption: cap('Bulk job with Spring Batch: a streamed CSV is read in chunks of 500, validated, de-duplicated against the (job_id, external_ref) ledger and written through NotificationService.submitInternal.', `${B}/batch/BulkJobService.java, ${B}/batch/BulkJobConfig.java, ${B}/batch/RecipientRowProcessor.java, ${B}/batch/BulkMessageWriter.java`),
  participants: [{ id: 'u', label: 'Client (SENDER + messages:bulk)' }, { id: 'bc', label: 'BulkJobController' }, { id: 'bs', label: 'BulkJobService' }, { id: 'sb', label: 'bulkSendJob' }, { id: 'w', label: 'BulkMessageWriter' }, { id: 'db', label: 'Database' }],
  steps: [
    { from: 'u', to: 'bc', text: 'POST /bulk-jobs (multipart CSV, templateCode, channel, ratePerSecond)' },
    { from: 'bc', to: 'bs', text: 'validate size, authz, scope messages:bulk' },
    { from: 'bs', to: 'db', text: 'insert bulk_job CREATED; store normalised upload' },
    { from: 'bs', to: 'sb', text: 'launch asynchronously' },
    { from: 'bc', to: 'u', text: '202 + job id + Location', kind: 'return' },
    { loop: 'chunk of 500 rows', steps: [
      { from: 'sb', to: 'sb', text: 'read, validate columns, resolve recipient' },
      { from: 'sb', to: 'db', text: 'bulk_job_row ledger insert (duplicate => failure row)' },
      { from: 'sb', to: 'w', text: 'items' },
      { from: 'w', to: 'db', text: 'submitInternal key bulk-<jobId>-<ref>, VALIDATED + outbox; rate limited' },
      { from: 'sb', to: 'db', text: 'commit chunk, update counters' },
    ] },
    { from: 'sb', to: 'db', text: 'COMPLETED, COMPLETED_WITH_ERRORS (failed > 0) or FAILED' },
  ],
};

DIAGRAMS.bulkRestart = {
  type: 'sequence',
  autonumber: true,
  caption: cap('Bulk restart: a FAILED or STOPPED job is relaunched with the same parameters. With concurrency 1 the reader resumes from the last committed chunk; with more workers the file is re-read and the ledger plus deterministic idempotency keys make processed rows no-ops.', `${B}/batch/BulkJobService.java (restart), ${B}/batch/BulkExecutionRecovery.java, ${B}/batch/BulkJobStore.java`),
  participants: [{ id: 'op', label: 'Operator / sender' }, { id: 'bs', label: 'BulkJobService' }, { id: 'sb', label: 'Spring Batch' }, { id: 'db', label: 'Database' }],
  steps: [
    { note: 'Execution failed or stopped mid-file; some chunks already committed', over: ['bs', 'db'] },
    { from: 'op', to: 'bs', text: 'POST /bulk-jobs/{id}/restart' },
    { alt: 'status not FAILED or STOPPED', steps: [{ from: 'bs', to: 'op', text: '409 ILLEGAL_STATE_TRANSITION', kind: 'return' }], else: { label: 'restartable', steps: [{ from: 'bs', to: 'sb', text: 'restart(executionId)' }] } },
    { from: 'sb', to: 'db', text: 'resume at last committed chunk (concurrency 1) or re-read from top' },
    { from: 'sb', to: 'db', text: 'ledger hit + same idempotency key => no second message' },
    { from: 'sb', to: 'db', text: 'final counters: processed = succeeded + failed + duplicate' },
  ],
};

DIAGRAMS.bulkStates = {
  type: 'state',
  caption: cap('Bulk job status values. Restart is permitted only from FAILED or STOPPED; ABANDONED is set by the sweeper for jobs without progress and is terminal for the API.', `${B}/domain/BulkJobStatus.java, ${B}/batch/BulkJobService.java, ${B}/batch/BulkJobSweeper.java`),
  states: [{ id: 'CREATED', initial: true }, { id: 'RUNNING', kind: 'warn' }, { id: 'COMPLETED', kind: 'ok' }, { id: 'COMPLETED_WITH_ERRORS', kind: 'ok' }, { id: 'FAILED', kind: 'error' }, { id: 'STOPPED', kind: 'neutral' }, { id: 'ABANDONED', kind: 'neutral' }],
  transitions: [
    { from: 'CREATED', to: 'RUNNING' }, { from: 'RUNNING', to: 'COMPLETED' }, { from: 'RUNNING', to: 'COMPLETED_WITH_ERRORS', label: 'failed > 0' },
    { from: 'RUNNING', to: 'FAILED' }, { from: 'RUNNING', to: 'STOPPED', label: 'stop' }, { from: 'RUNNING', to: 'ABANDONED', label: 'stale-after' },
    { from: 'FAILED', to: 'RUNNING', label: 'restart' }, { from: 'STOPPED', to: 'RUNNING', label: 'restart' },
  ],
};

// ---------------------------------------------------------------- Chapter 15: ER
DIAGRAMS.dataStores = {
  type: 'boxes-and-arrows',
  direction: 'LR',
  title: 'Data stores and what lives where',
  caption: cap('PostgreSQL is the only system of record. Redis is a fast path with database fallback; MongoDB holds redacted provider diagnostics and may be down without failing a message.', `${B}/coordination/ResilientCoordination.java, ${B}/diagnostics/ProviderDiagnosticsStore.java, docs/database/`),
  lanes: [
    { id: 'pg', label: 'PostgreSQL (authoritative)', color: BLUE, nodes: [{ id: 'm', label: 'messages, attempts, events' }, { id: 'o', label: 'outbox, idempotency, dedupe' }, { id: 'a', label: 'audit, templates, preferences' }, { id: 'b', label: 'Spring Batch metadata' }] },
    { id: 'rd', label: 'Redis (optional)', color: ORANGE, nodes: [{ id: 'r1', label: 'idempotency fast path' }, { id: 'r2', label: 'token buckets', sub: 'provider + API' }] },
    { id: 'mg', label: 'MongoDB (best effort)', color: GREEN, nodes: [{ id: 'd1', label: 'provider_diagnostics', sub: 'redacted raw payloads' }] },
  ],
  edges: [{ from: 'o', to: 'r1', style: 'dashed', label: 'fallback' }, { from: 'm', to: 'd1', style: 'dashed', label: 'write failure ignored' }],
};

// ---------------------------------------------------------------- Chapter 16: security
DIAGRAMS.securityLayers = {
  type: 'boxes-and-arrows',
  title: 'Defence in depth',
  caption: cap('Security controls by layer. Callbacks are permitAll at the Spring Security layer but authenticated by HMAC in the service, so the network is never trusted.', `${B}/config/SecurityConfig.java, ${B}/security/, ${B}/callback/SignatureVerifier.java, ${B}/common/Redaction.java`),
  lanes: [
    { id: 'edge', label: 'Edge', color: BLUE, nodes: [{ id: 'tls', label: 'TLS at ingress / GoRouter' }, { id: 'rl', label: 'RateLimitFilter', sub: '429 + Retry-After' }, { id: 'sz', label: 'RequestSizeFilter', sub: '256 KB, 413' }] },
    { id: 'authn', label: 'Authentication', color: GREEN, nodes: [{ id: 'jwt', label: 'JWT RS256 resource server', sub: 'sub, roles, tenant, scope' }, { id: 'hmac', label: 'HMAC-SHA256 callbacks', sub: 't + "." + rawBody, 5 min' }] },
    { id: 'authz', label: 'Authorization', color: ORANGE, nodes: [{ id: 'roles', label: '7 roles + scope messages:bulk' }, { id: 'ten', label: 'Tenant scoping', sub: '404 for foreign resources' }, { id: 'four', label: 'Four-eyes template approval' }] },
    { id: 'data', label: 'Data protection', color: PURPLE, nodes: [{ id: 'mask', label: 'Masked addresses in API' }, { id: 'red', label: 'Redaction in logs, audit, Mongo' }, { id: 'min', label: 'No content or PII on the bus' }] },
  ],
  edges: [{ from: 'tls', to: 'jwt' }, { from: 'rl', to: 'jwt', style: 'dashed' }, { from: 'jwt', to: 'roles' }, { from: 'hmac', to: 'ten', style: 'dashed' }, { from: 'roles', to: 'mask' }, { from: 'ten', to: 'red' }],
};

DIAGRAMS.trustZones = {
  type: 'boxes-and-arrows',
  direction: 'LR',
  title: 'Trust zones',
  caption: cap('Network and trust zones of the prod-like deployment (intended topology, not executed).', 'docs/architecture/deployment-views.md, infrastructure/kubernetes/base/networkpolicy.yaml'),
  lanes: [
    { id: 'pub', label: 'Public edge', color: RED, nodes: [{ id: 'ing', label: 'Ingress / GoRouter', sub: 'TLS, only /api and health' }] },
    { id: 'app', label: 'App zone', color: ORANGE, nodes: [{ id: 'pods', label: 'API + consumer pods', sub: 'egress allow-list' }] },
    { id: 'dat', label: 'Data zone', color: GREEN, nodes: [{ id: 'pg', label: 'PostgreSQL' }, { id: 'rb', label: 'RabbitMQ' }, { id: 'rd', label: 'Redis' }, { id: 'mg', label: 'MongoDB' }] },
    { id: 'eg', label: 'Provider egress', color: PURPLE, nodes: [{ id: 'pv', label: 'Providers', sub: 'simulated here' }] },
  ],
  edges: [{ from: 'ing', to: 'pods' }, { from: 'pods', to: 'pg' }, { from: 'pods', to: 'rb' }, { from: 'pods', to: 'pv', label: 'via proxy' }, { from: 'pv', to: 'ing', style: 'dashed', label: 'signed webhooks' }],
};

// ---------------------------------------------------------------- Chapter 17-18: deployment
DIAGRAMS.cf = {
  type: 'boxes-and-arrows',
  direction: 'LR',
  title: 'Cloud Foundry deployment',
  caption: cap('Cloud Foundry topology. NOT executed: no foundation was available. The manifest was parsed and structurally checked by validate-manifest.mjs.', 'cloud-foundry/manifest.yml, cloud-foundry/manifest-frontend.yml, cloud-foundry/scripts/'),
  lanes: [
    { id: 'edge', label: 'Edge', color: BLUE, nodes: [{ id: 'gr', label: 'GoRouter', sub: 'route per env' }] },
    { id: 'apps', label: 'Applications', color: GREEN, nodes: [{ id: 'be', label: 'messaging-backend-((env))', sub: 'java_buildpack, N instances, health http' }, { id: 'fe', label: 'messaging-console', sub: 'frontend manifest' }] },
    { id: 'svc', label: 'Bound services', color: ORANGE, nodes: [{ id: 'pg', label: 'messaging-postgres' }, { id: 'rb', label: 'messaging-rabbit' }, { id: 'rd', label: 'messaging-redis' }, { id: 'mg', label: 'messaging-mongo' }] },
    { id: 'sec', label: 'Secrets', color: PURPLE, nodes: [{ id: 'cred', label: 'cf set-env / user-provided service', sub: 'no secrets in manifest' }] },
  ],
  edges: [{ from: 'gr', to: 'be' }, { from: 'gr', to: 'fe' }, { from: 'be', to: 'pg' }, { from: 'be', to: 'rb' }, { from: 'be', to: 'rd' }, { from: 'be', to: 'mg' }, { from: 'cred', to: 'be', style: 'dashed' }],
};

DIAGRAMS.k8s = {
  type: 'boxes-and-arrows',
  title: 'Kubernetes deployment',
  caption: cap('Kubernetes topology from the kustomize base and Helm chart. NOT executed: no cluster, kubectl or Helm was available; the YAML was parsed and structurally checked only.', 'infrastructure/kubernetes/base/, infrastructure/kubernetes/overlays/, infrastructure/helm/messaging-platform/'),
  lanes: [
    { id: 'in', label: 'Entry', color: BLUE, nodes: [{ id: 'ing', label: 'Ingress', sub: 'ingress.yaml, TLS' }, { id: 'svc', label: 'Service', sub: 'service.yaml' }] },
    { id: 'wl', label: 'Workload (namespace messaging)', color: GREEN, nodes: [{ id: 'dep', label: 'Deployment', sub: 'deployment.yaml, probes, graceful shutdown' }, { id: 'hpa', label: 'HPA', sub: 'hpa.yaml' }, { id: 'pdb', label: 'PodDisruptionBudget', sub: 'pdb.yaml' }] },
    { id: 'cfg', label: 'Configuration', color: ORANGE, nodes: [{ id: 'cm', label: 'ConfigMap', sub: 'configmap.yaml' }, { id: 'sec', label: 'Secret template', sub: 'secret.template.yaml, dummy values' }, { id: 'sa', label: 'ServiceAccount', sub: 'serviceaccount.yaml' }] },
    { id: 'net', label: 'Policy and monitoring', color: PURPLE, nodes: [{ id: 'np', label: 'NetworkPolicy', sub: 'networkpolicy.yaml' }, { id: 'sm', label: 'ServiceMonitor', sub: 'servicemonitor.yaml' }] },
  ],
  edges: [{ from: 'ing', to: 'svc' }, { from: 'svc', to: 'dep' }, { from: 'hpa', to: 'dep', style: 'dashed', label: 'scales' }, { from: 'cm', to: 'dep' }, { from: 'sec', to: 'dep' }, { from: 'np', to: 'dep', style: 'dashed' }, { from: 'sm', to: 'dep', style: 'dashed', label: 'scrape' }],
};

DIAGRAMS.compose = {
  type: 'boxes-and-arrows',
  direction: 'LR',
  title: 'Docker Compose stack',
  caption: cap('Services declared in docker-compose.yml. NOT executed: Docker is not installed on the authoring machine; the file was parsed by scripts/validate-infra.sh only.', 'infrastructure/compose/docker-compose.yml, infrastructure/docker/Dockerfile.backend, infrastructure/docker/Dockerfile.frontend'),
  lanes: [
    { id: 'app', label: 'Application', color: GREEN, nodes: [{ id: 'fe', label: 'frontend', sub: 'nginx.conf' }, { id: 'be', label: 'backend', sub: 'Dockerfile.backend' }] },
    { id: 'data', label: 'Data', color: BLUE, nodes: [{ id: 'pg', label: 'postgres', sub: 'pg-data volume' }, { id: 'rb', label: 'rabbitmq', sub: 'rabbit-data volume' }, { id: 'rd', label: 'redis' }, { id: 'mg', label: 'mongo', sub: 'mongo-data volume' }] },
    { id: 'obs', label: 'Observability', color: ORANGE, nodes: [{ id: 'pr', label: 'prometheus' }, { id: 'gf', label: 'grafana' }, { id: 'ot', label: 'otel-collector' }] },
  ],
  edges: [{ from: 'fe', to: 'be' }, { from: 'be', to: 'pg' }, { from: 'be', to: 'rb' }, { from: 'be', to: 'rd' }, { from: 'be', to: 'mg' }, { from: 'pr', to: 'be', style: 'dashed', label: 'scrape' }, { from: 'be', to: 'ot', style: 'dashed', label: 'OTLP' }, { from: 'gf', to: 'pr' }],
};

DIAGRAMS.profiles = {
  type: 'boxes-and-arrows',
  direction: 'LR',
  title: 'Spring profiles and what they switch',
  caption: cap('The same jar runs everywhere; profiles select adapters. Only the local profile (single JVM) was executed.', 'backend/src/main/resources/application.yml, application-local.yml, application-test.yml, application-prod-like.yml'),
  lanes: [
    { id: 'l', label: 'local (executed)', color: GREEN, nodes: [{ id: 'l1', label: 'H2 in PostgreSQL mode' }, { id: 'l2', label: 'InMemoryMessageBus' }, { id: 'l3', label: 'In-memory Redis + Mongo' }, { id: 'l4', label: 'Dev token enabled' }] },
    { id: 't', label: 'test (executed)', color: BLUE, nodes: [{ id: 't1', label: 'Same, deterministic' }, { id: 't2', label: 'auto-start pipeline off by default' }] },
    { id: 'p', label: 'prod-like (NOT executed)', color: ORANGE, nodes: [{ id: 'p1', label: 'PostgreSQL + RabbitMQ (rabbit group)' }, { id: 'p2', label: 'Redis + MongoDB' }, { id: 'p3', label: 'JWT issuer-uri, no dev endpoints' }] },
  ],
  edges: [],
};

// ---------------------------------------------------------------- Chapter 19: CI/CD
DIAGRAMS.ci = {
  type: 'boxes-and-arrows',
  direction: 'LR',
  title: 'CI/CD pipeline stages',
  caption: cap('Pipeline stage map shared by GitHub Actions, Jenkins and Bamboo definitions. NOT executed on any CI server; YAML parsed locally, Jenkinsfile Groovy not linted.', '.github/workflows/ci.yml, .github/workflows/release.yml, Jenkinsfile, bamboo-specs/bamboo.yaml'),
  lanes: [
    { id: 's1', label: 'Build and test', color: BLUE, nodes: [{ id: 'v', label: 'Version' }, { id: 'be', label: 'Backend verify' }, { id: 'fe', label: 'Frontend lint, test, build' }, { id: 'sa', label: 'Static analysis' }] },
    { id: 's2', label: 'Security and integration', color: ORANGE, nodes: [{ id: 'ow', label: 'OWASP dependency-check' }, { id: 'na', label: 'npm audit' }, { id: 'it', label: 'Integration tests (-Pcontainers)' }, { id: 'cv', label: 'Config validation' }] },
    { id: 's3', label: 'Package', color: PURPLE, nodes: [{ id: 'dk', label: 'Docker build + Trivy + SBOM' }] },
    { id: 's4', label: 'Deploy (manual)', color: GREEN, nodes: [{ id: 'dt', label: 'Deploy to test (CF)' }, { id: 'sm', label: 'Post-deploy smoke' }] },
  ],
  edges: [{ from: 'be', to: 'ow' }, { from: 'fe', to: 'na' }, { from: 'sa', to: 'cv' }, { from: 'it', to: 'dk' }, { from: 'cv', to: 'dk' }, { from: 'dk', to: 'dt' }, { from: 'dt', to: 'sm' }],
};

// ---------------------------------------------------------------- Chapter 20: observability and performance
DIAGRAMS.observability = {
  type: 'boxes-and-arrows',
  direction: 'LR',
  title: 'Observability pipeline',
  caption: cap('Signals leave the backend as Prometheus metrics, OTLP traces and JSON logs. Config files were parsed only: PromQL was not checked by promtool and no dashboard was rendered.', 'observability/prometheus/, observability/otel/otel-collector.yaml, observability/grafana/, backend/src/main/java/com/example/messaging/metrics/MessagingMetrics.java'),
  lanes: [
    { id: 'src', label: 'Sources', color: GREEN, nodes: [{ id: 'mic', label: 'Micrometer', sub: '/actuator/prometheus' }, { id: 'trc', label: 'Tracing bridge', sub: 'OTLP, 10% in compose' }, { id: 'log', label: 'JSON logs', sub: 'MDC correlationId, tenant, messageId' }] },
    { id: 'col', label: 'Collection', color: BLUE, nodes: [{ id: 'prom', label: 'Prometheus', sub: 'alerts.yml, recording-rules.yml' }, { id: 'otel', label: 'OTel Collector', sub: 'debug exporter' }] },
    { id: 'out', label: 'Consumers', color: ORANGE, nodes: [{ id: 'gf', label: 'Grafana', sub: '5 generated dashboards' }, { id: 'am', label: 'Alertmanager', sub: '19 alerts with runbook links' }, { id: 'rb', label: 'Runbooks', sub: 'docs/operations/runbooks' }] },
  ],
  edges: [{ from: 'mic', to: 'prom' }, { from: 'trc', to: 'otel' }, { from: 'prom', to: 'gf' }, { from: 'prom', to: 'am' }, { from: 'am', to: 'rb' }],
};

DIAGRAMS.capacityThroughput = {
  type: 'bar',
  title: 'Illustrative: throughput per instance',
  unit: 'msg/s',
  caption: cap('ILLUSTRATIVE capacity arithmetic, not a measurement: throughput of one instance = workers / provider latency, for a 300 ms provider call (workers divided by 0.3 s).', 'docs/leadership/hld.md section 6, backend/src/main/resources/application.yml (messaging.dispatch.concurrency)'),
  data: [4, 8, 16, 32].map((w) => ({ label: `${w} workers`, value: +(w / 0.3).toFixed(1) })),
};
DIAGRAMS.capacityInstances = {
  type: 'bar',
  title: 'Illustrative: instances needed for 500 msg/s',
  unit: 'instances',
  caption: cap('ILLUSTRATIVE: instances needed to sustain 500 msg/s with 8 workers each, as provider latency varies. Real capacity is bounded by provider quota first. Computed, not measured.', 'docs/leadership/hld.md section 6'),
  data: [100, 300, 1000].map((ms) => ({ label: `${ms} ms provider`, value: Math.ceil(500 / (8 / (ms / 1000))) })),
};
DIAGRAMS.sloBurn = {
  type: 'hbar',
  title: 'Illustrative SLO targets',
  unit: 'seconds (p95)',
  caption: cap('ILLUSTRATIVE dispatch-delay p95 targets by priority from the SLI table; stakeholder-agreed values would replace them.', 'docs/operations/slis-slos.md, observability/prometheus/alerts.yml'),
  data: [{ label: 'CRITICAL', value: 5 }, { label: 'HIGH', value: 30 }, { label: 'NORMAL (5 min)', value: 300 }],
};

// ---------------------------------------------------------------- Chapter 21: AI workflow
DIAGRAMS.aiWorkflow = {
  type: 'boxes-and-arrows',
  direction: 'LR',
  title: 'AI-assisted development loop',
  caption: cap('Spec-driven loop used to build this repository with Claude Code: context, spec, small change, tests, independent review, then merge. Humans own decisions and invariants.', 'ai-engineering/spec-driven-workflow.md, ai-engineering/context-engineering.md, ai-engineering/multi-agent-workflow.md, .github/copilot-instructions.md'),
  lanes: [
    { id: 'c', label: 'Context', color: BLUE, nodes: [{ id: 'cm', label: 'AGENTS.md + context-map' }, { id: 'ct', label: 'ai-engineering/context/*.md' }] },
    { id: 's', label: 'Specify', color: GREEN, nodes: [{ id: 'sp', label: 'specs/NNNN-*.md', sub: 'acceptance criteria first' }] },
    { id: 'b', label: 'Build', color: ORANGE, nodes: [{ id: 'sk', label: 'ai-engineering/skills', sub: 'add-endpoint, add-state-transition ...' }, { id: 'ts', label: 'Tests with the change' }] },
    { id: 'r', label: 'Review', color: PURPLE, nodes: [{ id: 'cr', label: 'ai-code-review scenarios', sub: '10 failure patterns' }, { id: 'ck', label: 'checklists + CODEOWNERS' }] },
  ],
  edges: [{ from: 'cm', to: 'sp' }, { from: 'ct', to: 'sp' }, { from: 'sp', to: 'sk' }, { from: 'sk', to: 'ts' }, { from: 'ts', to: 'cr' }, { from: 'cr', to: 'ck' }],
};

// ---------------------------------------------------------------- Chapter 22: failure
DIAGRAMS.failureWindows = {
  type: 'sequence',
  autonumber: true,
  caption: cap('Crash windows along the pipeline and what recovers each one. No window loses an accepted message; the dangerous window (after provider accept, before persist) ends in DELIVERY_UNKNOWN rather than a blind resend.', `${B}/scheduler/StuckMessageSweeper.java, ${B}/messaging/DispatchConsumer.java, ${B}/scheduler/OutboxPublisher.java`),
  participants: [{ id: 'api', label: 'API' }, { id: 'db', label: 'Database' }, { id: 'ob', label: 'Outbox' }, { id: 'dc', label: 'Consumer' }, { id: 'pr', label: 'Provider' }],
  steps: [
    { from: 'api', to: 'db', text: 'commit message + outbox NEW' },
    { note: 'Crash A: after commit, before publish => row stays NEW, publisher retries', over: ['db', 'ob'] },
    { from: 'ob', to: 'dc', text: 'publish envelope' },
    { note: 'Crash B: after publish, before outbox SENT => duplicate envelope, consumer dedupe', over: ['ob', 'dc'] },
    { from: 'dc', to: 'db', text: 'claim: processed_message + CAS + attempt STARTED' },
    { note: 'Crash C: after claim, before provider call => sweeper after lock-ttl', over: ['dc', 'db'] },
    { from: 'dc', to: 'pr', text: 'send(idempotencyRef)' },
    { note: 'Crash D: after provider accept, before persist => DELIVERY_UNKNOWN, lookup or callback', over: ['dc', 'pr'] },
    { from: 'dc', to: 'db', text: 'persist outcome' },
    { note: 'Crash E: after persist, before ack => redelivery, dedupe by CAS', over: ['dc', 'db'] },
  ],
};

// ---------------------------------------------------------------- Chapter 23: mind map
DIAGRAMS.mindmap = {
  type: 'mindmap',
  center: 'Messaging Platform',
  caption: cap('Architecture mind map: the major concerns of the platform and the mechanisms that address each.', 'docs/architecture/architecture-overview.md, docs/adr/'),
  branches: [
    { label: 'Intake', children: [{ label: 'REST /api/v1' }, { label: 'Idempotency-Key' }, { label: 'Policy at accept' }, { label: 'Backpressure 503' }] },
    { label: 'Reliability', children: [{ label: 'Outbox' }, { label: 'At-least-once + dedupe' }, { label: 'Retry, backoff, jitter' }, { label: 'DLQ + replay' }] },
    { label: 'Lifecycle', children: [{ label: 'State machine, CAS' }, { label: 'DELIVERY_UNKNOWN' }, { label: 'Reconciliation lookup' }] },
    { label: 'Providers', children: [{ label: 'ChannelProvider port' }, { label: 'Circuit breaker' }, { label: 'Token bucket' }, { label: 'Simulators + Acme' }] },
    { label: 'Callbacks', children: [{ label: 'HMAC + replay guard' }, { label: 'Normalizer mappers' }, { label: 'Rank rule' }] },
    { label: 'Data', children: [{ label: 'PostgreSQL + Flyway' }, { label: 'Redis fast path' }, { label: 'Mongo diagnostics' }, { label: 'Retention + legal hold' }] },
    { label: 'Security', children: [{ label: 'JWT, 7 roles' }, { label: 'Tenant 404 policy' }, { label: 'Masking, redaction' }, { label: 'Four-eyes templates' }] },
    { label: 'Operations', children: [{ label: 'Micrometer, OTel' }, { label: 'SLOs, 19 alerts' }, { label: 'Runbooks' }, { label: 'CF, K8s, compose' }] },
    { label: 'Delivery', children: [{ label: 'Angular console' }, { label: 'CI/CD x3' }, { label: 'AI-assisted workflow' }] },
  ],
};

// ---------------------------------------------------------------- Chapter 25: leadership
DIAGRAMS.sprintPoints = {
  type: 'bar',
  title: 'Story points per sprint (example plan)',
  unit: 'story points',
  caption: cap('Planned story points per sprint from the example sprint breakdown (S1: 43, S2: 52, S3: 49). Relative estimates, not measurements.', 'docs/leadership/sprint-task-breakdown.md'),
  data: [{ label: 'Sprint 1', value: 43 }, { label: 'Sprint 2', value: 52 }, { label: 'Sprint 3', value: 49 }],
};
DIAGRAMS.riskScores = {
  type: 'hbar',
  title: 'Top risks by score (likelihood x impact)',
  unit: 'score',
  caption: cap('Highest-scoring risks from the risk register (R1, R4, R6, R9, R10, R12, R14, R15 all score 12).', 'docs/leadership/risk-register.md'),
  data: [
    { label: 'R1 Duplicate sends after ambiguity', value: 12 }, { label: 'R4 Provider outage or throttling', value: 12 }, { label: 'R6 Retry storm', value: 12 },
    { label: 'R9 Template error at scale', value: 12 }, { label: 'R14 Pool exhaustion cascade', value: 12 }, { label: 'R15 AI code violates invariants', value: 12 },
    { label: 'R2 Loss between DB and broker', value: 10 }, { label: 'R3 Cross-tenant exposure', value: 10 },
  ],
};
