# 4. Angular and Backend Architecture

## 4.1 The Angular console

The console (`frontend/messaging-console`) is an Angular 20 application built from **standalone components**, **Signals** and **lazy routes**, with Angular Material 20 for components. It is the operator and author interface; service clients use the REST API directly. Its role is to make the workflows of this document tangible: submit a message, watch its lifecycle timeline, approve a template, inspect a dead letter, replay it.

{{diagram:angular}}

### Routes

Every feature page is lazy loaded (`loadComponent`) from `frontend/messaging-console/src/app/app.routes.ts`. Role guards use `data.roles` taken from the `ACCESS` matrix in `core/auth/access.ts`, which mirrors the endpoint role table of the contract. The guards are **UX only**: the backend answers 403 or 404 whatever the UI shows.

| Route | Page | Roles that see it |
|---|---|---|
| `/login` | Dev-token persona picker | everyone (local profile only) |
| `/dashboard` | Counts, success rate, p95 dispatch delay | any authenticated |
| `/messages`, `/messages/:id` | List with filters; detail with lifecycle timeline | any authenticated |
| `/messages/new` | Reactive submit form, idempotency key generated | SENDER, TENANT_ADMIN, PLATFORM_ADMIN |
| `/templates`, `/templates/:id` | Versions, locale tabs, preview, submit and approve | any authenticated (actions by role) |
| `/preferences` | Recipient search and edit with optimistic-lock conflict UX | OPERATOR, TENANT_ADMIN, PLATFORM_ADMIN, SENDER (edit: not SENDER) |
| `/bulk-jobs`, `/bulk-jobs/:id` | Jobs with progress polling | SENDER, TENANT_ADMIN, PLATFORM_ADMIN |
| `/providers` | Health, circuit state, fault injection when allowed | OPERATOR, TENANT_ADMIN, PLATFORM_ADMIN |
| `/queues` | Depth per priority, retry and DLQ counts | OPERATOR, PLATFORM_ADMIN |
| `/failed`, `/dead-letters` | Investigation lists, replay, discard | OPERATOR, TENANT_ADMIN, PLATFORM_ADMIN |
| `/delivery-events`, `/audit` | Search | OPERATOR, AUDITOR, TENANT_ADMIN, PLATFORM_ADMIN / AUDITOR, TENANT_ADMIN, PLATFORM_ADMIN |
| `/admin` | Pause consumers, provider config | PLATFORM_ADMIN |

### Cross-cutting frontend pieces

| Concern | Where | Behaviour |
|---|---|---|
| Authentication | `core/auth/auth.service.ts`, `guards.ts` | Dev JWT held in memory and `sessionStorage`; guards redirect to `/login` or `/forbidden` |
| HTTP | `core/http/interceptors.ts` | Adds `Authorization`, a correlation id; maps errors; drives the loading indicator |
| Error text | `core/http/error-mapper.ts` | Maps the backend `ApiError.code` to an actionable sentence |
| Form validation | `core/forms/validators.ts` | Per-channel validators (E.164 phone, subject rules) mirroring server rules |
| Shared widgets | `shared/` | `data-table`, `pager`, `status-chip`, `timeline`, `masked-value`, `confirm-dialog`, `error-banner`, `charts` |
| Tests | `*.spec.ts` | Karma and Jasmine unit tests for services, interceptors, guards and key components |

> [!NOTE] Known frontend deviation
> The deviations record states that the history screen's `HistoryEntry` model expects a flat shape while `GET /messages/{id}/history` returns the specified `TimelineEntry {at, kind, type, summary, detail: object}`. The mapping change is recorded as still needed in the frontend; see Appendix B.

### Console screenshots

The following screenshots were recorded from the running console by a UI check against the local backend and are stored in `docs/validation/screenshots/` (see `docs/validation/VALIDATION_REPORT.md` for how they were captured). All data shown is synthetic.

![Dashboard: counts by status and channel, success rate and p95 dispatch delay for the platform-admin persona. Source: docs/validation/screenshots/02-dashboard.png](docs/validation/screenshots/02-dashboard.png)

![Messages list with filters and the PageResponse paging. Source: docs/validation/screenshots/03-messages.png](docs/validation/screenshots/03-messages.png)

![Template detail: versions, locale tabs, preview and approval actions. Source: docs/validation/screenshots/07-template-detail.png](docs/validation/screenshots/07-template-detail.png)

![Queues view: depth per priority, in-flight, consumers, DLQ and retry-pending counts. Source: docs/validation/screenshots/08-queues.png](docs/validation/screenshots/08-queues.png)

![Providers view: health, circuit state, rate limit and simulator fault mode. Source: docs/validation/screenshots/09-providers.png](docs/validation/screenshots/09-providers.png)

![Dead letters view with replay and discard actions for operators. Source: docs/validation/screenshots/11-dead-letters.png](docs/validation/screenshots/11-dead-letters.png)

## 4.2 Backend layering

A request passes through a fixed chain. Filters are ordered so that cheap rejections happen first and so that every log line carries a correlation id.

{{diagram:requestChain}}

### Controllers and DTOs

Controllers in `com.example.messaging.web` are thin: they bind a DTO record, check coarse authorization, call one service method and map the result. All DTOs are Java records under `web/dto` (`MessageDtos`, `TemplateDtos`, `RecipientDtos`, `BulkJobDtos`, `OpsDtos`, `AuditDtos`). Error mapping is centralised in `GlobalExceptionHandler`, which converts exceptions to the stable `ApiError` payload (Chapter 24 lists the codes).

### Services

`NotificationService` is the heart: submission, query, cancel, requeue, replay, discard, and the single `transition(...)` method through which **every** status change passes. `transition` takes the expected `from` status, so it is a compare-and-set and returns whether this caller won. No other class writes the status column.

```java
public boolean transition(String messageId, MessageStatus from, MessageStatus to,
        String reason, String actor) { ... }
```

### Domain and persistence

Entities extend `PersistableEntity`. Optimistic locking (`@Version`) protects `recipient`, `recipient_preference`, `template_version`, `message_request` and `bulk_job`. Repositories avoid N+1 queries with fetch joins, entity graphs and projections (`MessageStatusView`, `StatusCount`, `ChannelCount`); `RepositoryQueryCountIT` guards this.

JPA runs with `ddl-auto: validate` (Flyway owns the schema), `open-in-view: false`, and `hibernate.jdbc.time_zone=UTC`.

## 4.3 Configuration model

Configuration is typed. `messaging.*` keys bind to records in `config/MessagingProperties`, `config/PipelineProperties`, `config/SimulatorProperties`, `batch/BulkProperties` and `scheduler/RetentionProperties`. There is **no default profile**: the application must be started with `--spring.profiles.active=local`, `test` or `prod-like`, so that a forgotten profile cannot enable the dev token issuer (a secure-by-default decision recorded in the deviations).

| Profile | Database | Bus | Coordination | Mongo | Security |
|---|---|---|---|---|---|
| `local` | H2 (PostgreSQL mode) | `InMemoryMessageBus` | in-memory | in-memory server | `POST /dev/token` enabled |
| `test` | H2, unique per context | in-memory | in-memory | in-memory | deterministic, pipeline auto-start off unless the test class turns it on |
| `prod-like` (group `rabbit`) | PostgreSQL | `RabbitMessageBus` | Redis | MongoDB | JWT `issuer-uri`, no dev endpoints |

## 4.4 Error handling philosophy

Errors are part of the API. The rules, implemented in `GlobalExceptionHandler`:

1. Every error body is an `ApiError {timestamp, status, error, code, message, path, correlationId, details[]}`.
2. `code` is stable and machine-readable; `message` is for people and never contains a stack trace (`server.error.include-*` are `never`).
3. Database connection failures answer **503** with `Retry-After: 5` (fail closed; clients retry with the same idempotency key) instead of a 500.
4. Resources owned by another tenant answer **404**, not 403, so existence is not disclosed.
5. Unique-constraint conflicts map to the additive code `DUPLICATE_RESOURCE` (409), not to a misleading optimistic-lock code.

## Reference material

{{embed:docs/leadership/lld.md|title=Low-level design (design intent)|note=This document records design intent. The implemented consumer differs in order: cancel, expiry, policy, provider selection, circuit state and the token bucket are checked before the claim, and the claim is one transaction (see Chapter 7 and DEVIATIONS "Backend stage 2", item 1).}}

{{embed:docs/leadership/api-contract-guide.md|title=API contract guide}}
