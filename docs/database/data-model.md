# Data Model

Authoritative DDL: Flyway migrations in `backend/src/main/resources/db/migration` (see source). This document explains purpose, constraints and access patterns. ER diagram: `docs/architecture/er-diagram.md`. Portable-SQL rule (ADR-0005): types `VARCHAR`, `BIGINT`, `INT`, `BOOLEAN`, `TIMESTAMP`; no partial indexes, JSONB or `ON CONFLICT`.

## Conventions
- `id VARCHAR(36)` UUID (`common.Ids`). Timestamps are UTC `TIMESTAMP` (`created_at`, `updated_at`) written from the injected `Clock`.
- `version BIGINT` (optimistic locking, JPA `@Version`) on: `recipient`, `recipient_preference`, `template_version`, `message_request`, `bulk_job`.
- JSON-ish columns (`payload_json`, `variables_json`, `detail_json`) are text with application-side validation.
- Tenant-owned tables carry `tenant_id` and every repository method used by the web layer filters on it (ADR-0011).

## Table catalogue
| Table | Purpose | Key constraints | Written by | Retention |
|---|---|---|---|---|
| `tenant` | Business-unit boundary | PK id | admin/seed | permanent |
| `recipient` | Synthetic person and addresses | UNIQUE(tenant_id, external_ref) | seed/API | `deleted` soft flag; purge per policy |
| `recipient_preference` | Consent, channel enablement, quiet hours | PK recipient_id, `version` | PreferenceService | with recipient |
| `suppression` | Hard blocks by address hash | UNIQUE(tenant_id, channel, address_hash) | callbacks, operators | long (reputation) |
| `template` | Template identity | UNIQUE(tenant_id, code, channel) | TemplateService | permanent |
| `template_version` | Versioned content per locale | UNIQUE(template_id, version_no, locale) | TemplateService | permanent (audit of what was sent) |
| `message_request` | The message and its lifecycle | UNIQUE(tenant_id, idempotency_key); `version` | NotificationService, DispatchService, callbacks | `messaging.retention.message-days` |
| `message_attempt` | One provider try | UNIQUE(message_id, attempt_no) | DispatchService | with message |
| `provider_config` | Provider enablement and limits | UNIQUE(provider_id) | PLATFORM_ADMIN | permanent |
| `delivery_event` | Normalized callback events | UNIQUE(provider_id, provider_event_id) | DeliveryEventProcessor | `event-days` |
| `bulk_job`, `bulk_job_failure` | Bulk send tracking | PK; `version` | batch | with message retention |
| `audit_event` | Append-only audit | no update/delete by app | AuditService | `audit-days` (longest) |
| `idempotency_record` | API dedupe | UNIQUE(tenant_id, idem_key) | IdempotencyService | `expires_at` |
| `processed_message` | Consumer dedupe | UNIQUE(dispatch_id) | DispatchConsumer | short (days) |
| `outbox_event` | Reliable publish | status NEW/SENT | submission, OutboxPublisher | purge SENT after days |
| `replay_guard` | Callback replay cache | PK event_id | ReplayGuard | short (>= tolerance window, hours) |
| `BATCH_*` | Spring Batch metadata | per Spring | Spring Batch | with job history |

## Indexes (CONTRACT 4) and their query
| Index | Serves |
|---|---|
| `message_request(tenant_id, status, created_at DESC)` | message list filtered by status, console dashboards |
| `message_request(recipient_id, created_at)` | recipient history |
| `message_request(status, next_attempt_at)` | retry poller |
| `message_request(status, scheduled_at)` | scheduled poller |
| `message_request(correlation_id)` | support lookup |
| `message_request(bulk_job_id)` | bulk job drill-down |
| `audit_event(entity_type, entity_id, occurred_at)` | `/history` timelines |
| `audit_event(tenant_id, occurred_at)` | audit search |
Further: the unique constraints above double as indexes. Candidate indexes not in CONTRACT: `outbox_event(status, created_at)`, `delivery_event(message_id)`, `message_attempt(message_id)` covered by unique, `suppression` lookup by hash covered by unique. See `query-plans-and-indexes.md`.

## State and invariants (enforced where)
| Invariant | Enforced by |
|---|---|
| status transitions legal | `MessageStateMachine` + CAS updates (not a DB check constraint, because the table is data in code) |
| one attempt number per message | UNIQUE(message_id, attempt_no) |
| one provider event applied once | UNIQUE(provider_id, provider_event_id) |
| idempotent submission | UNIQUE(tenant_id, idempotency_key), UNIQUE(tenant_id, idem_key) |
| consent not stored with content | variables stored, rendered body never stored |
| append-only audit | no update/delete repository methods; DB privileges recommended in prod (REVOKE UPDATE, DELETE) |

## Transactional boundaries
- Submission: one transaction (message, outbox, idempotency, audit).
- Dispatch: separate short transactions: (1) dedupe+CAS to PROCESSING, (2) attempt STARTED, (3) outcome+transition. **No transaction is held across the provider call.**
- Callback: one transaction per event (insert event + CAS transition + audit).
- See `transactions-and-connection-pooling.md`.

## Seed data (profiles local, test only)
2 tenants (`tenant-retail`, `tenant-wealth`), 60 synthetic recipients, 8 templates (en/es), 4 provider configs. No real data.
