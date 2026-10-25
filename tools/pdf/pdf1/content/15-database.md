# 15. Database ER Diagram

## 15.1 Design stance

PostgreSQL 16 is the **single system of record** (ADR-0005). The schema is owned by Flyway migrations in `backend/src/main/resources/db/migration` (JPA runs with `ddl-auto: validate`). SQL is deliberately **portable**: only `VARCHAR`, `BIGINT`, `INT`, `BOOLEAN`, `TIMESTAMP` and plain constraints, with no partial indexes, JSONB or `ON CONFLICT`, so the same migrations run on H2 in PostgreSQL mode for local runs and tests, and map mechanically to Oracle (`docs/database/oracle-mapping.md`, reference DDL not executed).

| Migration | Content |
|---|---|
| `V1__tenant_recipient.sql` | `tenant`, `recipient`, `recipient_preference`, `suppression` |
| `V2__templates.sql` | `template`, `template_version` |
| `V3__messages.sql` | `message_request`, `message_attempt`, `provider_config`, `delivery_event` |
| `V4__bulk_audit_idempotency_outbox.sql` | `bulk_job`, `bulk_job_failure`, `audit_event`, `idempotency_record`, `processed_message`, `outbox_event`, `replay_guard` |
| `V50__dispatch_indexes.sql` | Indexes for the dispatch pipeline queries |
| `V100__spring_batch.sql` | Spring Batch metadata tables |
| `V200__bulk_ledger_legal_hold.sql` | `bulk_job_row`, `legal_hold`, unique failure line index, retention age indexes |

## 15.2 Entity-relationship diagrams

Four figures cover all 19 application tables: an overview of the foreign-key structure, then three detail figures for identity and templates, the message core, and the reliability and bulk tables.

> [!NOTE] Reading the diagrams
> Only key and relationship columns are drawn in the detail figures; the migrations are authoritative. `outbox_event.aggregate_id` and `processed_message.dispatch_id` are logical references only (no foreign key), by design: dedupe and outbox rows must be insertable and purgeable independently of the message row. `bulk_job.spring_batch_execution_id` references the Spring Batch metadata tables logically.

{{diagram:erOverview}}

{{diagram:erIdentity}}

{{diagram:erMessaging}}

{{diagram:erSupport}}

## 15.3 Table catalogue

| Table | Purpose | Constraints that carry business meaning |
|---|---|---|
| `tenant` | Tenant root | `status` |
| `recipient` | Synthetic recipients | `UNIQUE(tenant_id, external_ref)`; `deleted` flag; `version` |
| `recipient_preference` | Marketing opt-in, channel flags, quiet hours, fallback order | One row per recipient; `version` for optimistic locking |
| `suppression` | Hard blocks by address hash | `UNIQUE(tenant_id, channel, address_hash)` |
| `template` | Template identity and category | `UNIQUE(tenant_id, code, channel)` |
| `template_version` | Versioned, localised content | `UNIQUE(template_id, version_no, locale)` |
| `message_request` | The message and its lifecycle | `UNIQUE(tenant_id, idempotency_key)`; `status`, `attempt_count`, `max_attempts`, `next_attempt_at`, `locked_until`, `channel_used`, `bulk_job_id`, `dlq_reason`, `discarded`; `version` |
| `message_attempt` | One row per provider attempt | `UNIQUE(message_id, attempt_no)`; `state` STARTED or FINISHED; outcome, provider message id, error code, latency |
| `provider_config` | Provider routing and limits | `UNIQUE(provider_id)`; `priority_order`, `rate_limit_per_sec`, `timeout_ms`, `hmac_key_ref`, `version` |
| `delivery_event` | Normalised provider receipts | `UNIQUE(provider_id, provider_event_id)`; `applied`, `ignored_reason`, `raw_ref` |
| `bulk_job` | Bulk job and counters | `version`; counters total, processed, succeeded, failed, duplicate |
| `bulk_job_failure` | Per-line failures | Unique `(job_id, line_no)`; `line_no = 0` means a job-level failure |
| `bulk_job_row` | Row ledger | Primary key `(job_id, external_ref)` |
| `audit_event` | Append-only audit | Indexes by entity and by tenant and time |
| `idempotency_record` | API idempotency | `UNIQUE(tenant_id, idem_key)`; `IN_PROGRESS` or `COMPLETED`; stored response |
| `processed_message` | Consumer dedupe | `dispatch_id` unique |
| `outbox_event` | Transactional outbox | `status` NEW or SENT; `available_at` for deferred rows |
| `replay_guard` | Callback replay memory | `event_id` primary key |
| `legal_hold` | Retention exemption | Scope TENANT or MESSAGE; active while `released_at IS NULL` |

Beyond the contract's column list, `message_request` gained `client_reference`, `options_json`, `metadata_json`, `fallback_channels`, `fallback_index`, `locked_until`, `dlq_reason`, `discarded` and `status_changed_at`; `provider_config.version`, `message_attempt.state`, `outbox_event.available_at` and `bulk_job.template_code/channel/category` were added too (DEVIATIONS).

## 15.4 Access patterns and indexes

The hot queries drive the index set. Plan shapes in `docs/database/query-plans-and-indexes.md` are labelled ILLUSTRATIVE unless a block is marked `EXECUTED`; see `docs/validation/VALIDATION_REPORT.md` for any executed plans.

| Pattern | Index |
|---|---|
| Message list by tenant and status, newest first | `idx_msg_tenant_status_created (tenant_id, status, created_at DESC)` |
| Recipient history | `idx_msg_recipient_created (recipient_id, created_at)` |
| Retry poller | `idx_msg_status_next_attempt (status, next_attempt_at)` |
| Scheduled dispatch | `idx_msg_status_scheduled (status, scheduled_at)` |
| Stuck sweeper | `idx_msg_status_locked (status, locked_until)` and `idx_msg_status_changed (status, status_changed_at)` |
| Correlation and bulk | `idx_msg_correlation`, `idx_msg_bulk_job`, `idx_msg_client_reference (tenant_id, client_reference)` |
| Outbox claim | `idx_outbox_status_available (status, available_at)`, `idx_outbox_aggregate_status` |
| Audit by entity and action | `idx_audit_entity`, `idx_audit_entity_action`, `idx_audit_tenant_time` |
| Reconciliation and callbacks | `idx_attempt_provider_msg (provider_id, provider_message_id)`, `idx_event_message` |
| Retention scans | `idx_msg_created`, `idx_audit_occurred`, `idx_event_received`, `idx_processed_at`, `idx_replay_seen` |

## 15.5 Transactions and the connection pool

The central rule: **no database transaction is held across a provider call.** The consumer commits the claim, calls the provider outside any transaction, then opens a new short transaction for the outcome. HikariPool exhaustion is therefore bounded by DB time, not provider latency. A `DataSource` fault wrapper in `FailureRecoveryIT` proves that a database outage yields 503 with nothing persisted (scenario 6).

## 15.6 Other stores

{{diagram:dataStores}}

## 15.7 Retention and legal hold

`RetentionJob` (cron `0 30 2 * * *`, batch-size and pause bounded, dry-run supported) purges by age: messages after `message-days` (90), audit after `audit-days` (365), events after `event-days` (30), `processed_message` older than the replay horizon, sent outbox rows, `replay_guard` rows older than `replay-window`, and bulk upload files. A **legal hold** (`legal_hold`, scope TENANT or MESSAGE) exempts data until released. Meters: `messaging.retention.deleted{table}`, `messaging.retention.run{dryRun}`; audit action `RETENTION_PURGE`.

## Reference material

{{embed:docs/database/data-model.md|title=Data model}}

{{embed:docs/database/query-plans-and-indexes.md|title=Query plans and indexes}}

{{embed:docs/database/transactions-and-connection-pooling.md|title=Transactions and connection pooling}}

{{embed:docs/database/mongodb-usage.md|title=MongoDB usage}}

{{embed:docs/database/retention-and-archival.md|title=Retention and archival}}

{{embed:docs/database/schema-evolution.md|title=Schema evolution}}

{{embed:docs/database/oracle-mapping.md|title=Oracle mapping}}

{{embed:docs/adr/0005-database-postgresql-vs-mysql-oracle-and-nosql.md|title=ADR-0005: Database choice}}
