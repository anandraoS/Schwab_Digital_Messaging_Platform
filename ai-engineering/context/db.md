# Context: Database
Load when: migrations, queries, repositories, performance. Authority: CONTRACT section 4, ADR-0005, `docs/database/`.

- PostgreSQL 16 prod; H2 2.x `MODE=PostgreSQL` local/test. Flyway `V<n>__desc.sql`. Spring Batch tables `V100__spring_batch.sql`. Seed only for `local,test`.
- Portable SQL only: no partial indexes, JSONB, `ON CONFLICT`, vendor hints. Types: `VARCHAR`, `BIGINT`, `INT`, `BOOLEAN`, `TIMESTAMP` (UTC). JSON as text.
- Ids: UUID `VARCHAR(36)`. `version BIGINT` optimistic lock on recipient, recipient_preference, template_version, message_request, bulk_job.
- Tables: tenant, recipient, recipient_preference, suppression, template, template_version, message_request, message_attempt, provider_config, delivery_event, bulk_job, bulk_job_failure, audit_event, idempotency_record, processed_message, outbox_event, replay_guard.
- Key uniques: `(tenant_id, idempotency_key)`, `(tenant_id, idem_key)`, `(message_id, attempt_no)`, `(provider_id, provider_event_id)`, `dispatch_id`, `(tenant_id, code, channel)`, `(template_id, version_no, locale)`.
- Indexes on message_request: (tenant_id,status,created_at DESC), (recipient_id,created_at), (status,next_attempt_at), (status,scheduled_at), (correlation_id), (bulk_job_id).
- Concurrency: CAS updates, no SELECT-then-UPDATE; unique-violation = dedupe signal. READ COMMITTED.
- Never hold a transaction across a provider call. No N+1 (EntityGraph/projections; assert statement count).
- Variables stored, rendered body never stored. Audit append-only.
- Migrations: expand-only, one concern, never edit merged ones, update `db/oracle` reference DDL.
- Retention via `RetentionJob`, chunked deletes; never delete non-terminal rows.
