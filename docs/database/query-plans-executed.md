# Query plans executed on H2 (PostgreSQL mode)

**These are H2 `EXPLAIN ANALYZE` plans, not PostgreSQL plans.** They were produced by `QueryPlanIT` (`bash scripts/it.sh QueryPlanIT` or `bash scripts/mvn.sh verify`) against the Flyway-built schema with 100000 synthetic messages, 100000 delivery events, 200000 audit events, 100000 outbox rows and 100000 attempts (generated in 22.4 s, then `ANALYZE`). `scanCount` is the number of index/table rows H2 actually read. The SQL is the equivalent of the repository/Specification output (literal values instead of bind parameters).


## Summary of findings (read this first)
- 17 hot queries were explained; **none needs a full table scan** (the test fails on any `tableScan`). Point lookups (delivery-event dedupe, idempotency, consumer dedupe,
  callback provider-message-id lookup, audit by entity, events by message, correlation id) read 2-3 rows each. Outbox poll, due-retry poll and stale-QUEUED poll read about
  1,000 / 1,000 / 100 index rows for a page of 100 (they ride the `(status, ...)` indexes).
- **Known H2 limitation, not proven on PostgreSQL:** the list queries sort by `created_at DESC, id ASC` (stable paging). H2 cannot use an index for ORDER BY when the
  leading index columns are equality-filtered, so for `tenant + status` it reads the whole partition (scanCount 56,001) and sorts; the tenant-only list reads 80,001 rows;
  the audit tenant/time search reads about 100,800 rows. PostgreSQL can walk `(tenant_id, status, created_at DESC)` in order (incremental sort for the `id` tie-breaker), so it
  is expected to read about one page, but **that was not executed here**: verify with `EXPLAIN (ANALYZE, BUFFERS)` on PostgreSQL 16 before relying on it. An attempt to
  "fix" this on H2 by appending `id` to the index did not change the H2 plan and was reverted (no speculative migration).
- Dashboard aggregate (`counts by status, last 24 h`) reads every row of the window by design; `/ops/summary` was the slowest read in `performance-results.md`.
- Retention scan: H2 chose the status index and sorts (nightly batch; fine here, planner-dependent elsewhere).

## Message list: tenant + status filter, newest first (page 1)

- Code path: `NotificationService.list / Specs.eq(tenantId,status)`
- Expected access path: idx_msg_tenant_status_created; H2 limitation: ORDER BY created_at DESC, id cannot use an index whose leading columns are equality-filtered, so H2 reads the whole tenant/status partition and sorts

```sql
select * from message_request where tenant_id = 'tenant-retail' and status = 'SENT_TO_PROVIDER' order by created_at desc, id asc fetch first 20 rows only
```

```
SELECT
    "public"."message_request"."id",
    "public"."message_request"."tenant_id",
    "public"."message_request"."client_app",
    "public"."message_request"."client_reference",
    "public"."message_request"."recipient_id",
    "public"."message_request"."channel",
    "public"."message_request"."category",
    "public"."message_request"."priority",
    "public"."message_request"."status",
    "public"."message_request"."status_reason",
    "public"."message_request"."template_code",
    "public"."message_request"."template_version_no",
    "public"."message_request"."template_locale",
    "public"."message_request"."payload_json",
    "public"."message_request"."options_json",
    "public"."message_request"."metadata_json",
    "public"."message_request"."fallback_channels",
    "public"."message_request"."fallback_index",
    "public"."message_request"."correlation_id",
    "public"."message_request"."idempotency_key",
    "public"."message_request"."request_hash",
    "public"."message_request"."scheduled_at",
    "public"."message_request"."expires_at",
    "public"."message_request"."attempt_count",
    "public"."message_request"."max_attempts",
    "public"."message_request"."next_attempt_at",
    "public"."message_request"."channel_used",
    "public"."message_request"."locked_until",
    "public"."message_request"."dlq_reason",
    "public"."message_request"."discarded",
    "public"."message_request"."bulk_job_id",
    "public"."message_request"."status_changed_at",
    "public"."message_request"."created_at",
    "public"."message_request"."updated_at",
    "public"."message_request"."version"
FROM "public"."message_request"
    /* public.idx_msg_tenant_status_created: tenant_id = 'tenant-retail'
        AND status = 'SENT_TO_PROVIDER'
     */
    /* scanCount: 56001 */
WHERE ("tenant_id" = 'tenant-retail')
    AND ("status" = 'SENT_TO_PROVIDER')
ORDER BY 33 DESC, 1
FETCH FIRST 20 ROWS ONLY
```

Result: index access, scanCount=56001

## Message list: tenant + status + channel + created_at range

- Code path: `NotificationService.list`
- Expected access path: range on created_at inside the (tenant, status, created_at) index; channel is filtered after the index

```sql
select * from message_request where tenant_id = 'tenant-retail' and status = 'DELIVERED' and channel = 'SMS' and created_at >= timestamp '2026-10-01 00:00:00' order by created_at desc, id asc fetch first 20 rows only
```

```
SELECT
    "public"."message_request"."id",
    "public"."message_request"."tenant_id",
    "public"."message_request"."client_app",
    "public"."message_request"."client_reference",
    "public"."message_request"."recipient_id",
    "public"."message_request"."channel",
    "public"."message_request"."category",
    "public"."message_request"."priority",
    "public"."message_request"."status",
    "public"."message_request"."status_reason",
    "public"."message_request"."template_code",
    "public"."message_request"."template_version_no",
    "public"."message_request"."template_locale",
    "public"."message_request"."payload_json",
    "public"."message_request"."options_json",
    "public"."message_request"."metadata_json",
    "public"."message_request"."fallback_channels",
    "public"."message_request"."fallback_index",
    "public"."message_request"."correlation_id",
    "public"."message_request"."idempotency_key",
    "public"."message_request"."request_hash",
    "public"."message_request"."scheduled_at",
    "public"."message_request"."expires_at",
    "public"."message_request"."attempt_count",
    "public"."message_request"."max_attempts",
    "public"."message_request"."next_attempt_at",
    "public"."message_request"."channel_used",
    "public"."message_request"."locked_until",
    "public"."message_request"."dlq_reason",
    "public"."message_request"."discarded",
    "public"."message_request"."bulk_job_id",
    "public"."message_request"."status_changed_at",
    "public"."message_request"."created_at",
    "public"."message_request"."updated_at",
    "public"."message_request"."version"
FROM "public"."message_request"
    /* public.idx_msg_tenant_status_created: created_at >= TIMESTAMP '2026-10-01 00:00:00'
        AND tenant_id = 'tenant-retail'
        AND status = 'DELIVERED'
     */
    /* scanCount: 12061 */
WHERE ("channel" = 'SMS')
    AND ("created_at" >= TIMESTAMP '2026-10-01 00:00:00')
    AND ("tenant_id" = 'tenant-retail')
    AND ("status" = 'DELIVERED')
ORDER BY 33 DESC, 1
FETCH FIRST 20 ROWS ONLY
```

Result: index access, scanCount=12061

## Message list: tenant only (no filter), newest first - the default console view

- Code path: `NotificationService.list`
- Expected access path: (tenant, status, created_at) index, then a top-N sort; see the H2 limitation note

```sql
select * from message_request where tenant_id = 'tenant-retail' order by created_at desc, id asc fetch first 20 rows only
```

```
SELECT
    "public"."message_request"."id",
    "public"."message_request"."tenant_id",
    "public"."message_request"."client_app",
    "public"."message_request"."client_reference",
    "public"."message_request"."recipient_id",
    "public"."message_request"."channel",
    "public"."message_request"."category",
    "public"."message_request"."priority",
    "public"."message_request"."status",
    "public"."message_request"."status_reason",
    "public"."message_request"."template_code",
    "public"."message_request"."template_version_no",
    "public"."message_request"."template_locale",
    "public"."message_request"."payload_json",
    "public"."message_request"."options_json",
    "public"."message_request"."metadata_json",
    "public"."message_request"."fallback_channels",
    "public"."message_request"."fallback_index",
    "public"."message_request"."correlation_id",
    "public"."message_request"."idempotency_key",
    "public"."message_request"."request_hash",
    "public"."message_request"."scheduled_at",
    "public"."message_request"."expires_at",
    "public"."message_request"."attempt_count",
    "public"."message_request"."max_attempts",
    "public"."message_request"."next_attempt_at",
    "public"."message_request"."channel_used",
    "public"."message_request"."locked_until",
    "public"."message_request"."dlq_reason",
    "public"."message_request"."discarded",
    "public"."message_request"."bulk_job_id",
    "public"."message_request"."status_changed_at",
    "public"."message_request"."created_at",
    "public"."message_request"."updated_at",
    "public"."message_request"."version"
FROM "public"."message_request"
    /* public.fk_msg_tenant_INDEX_F: tenant_id = 'tenant-retail' */
    /* scanCount: 80001 */
WHERE "tenant_id" = 'tenant-retail'
ORDER BY 33 DESC, 1
FETCH FIRST 20 ROWS ONLY
```

Result: index access, scanCount=80001

## Message list: platform admin (all tenants), newest first

- Code path: `NotificationService.list (tenant = null)`
- Expected access path: idx_msg_created

```sql
select * from message_request order by created_at desc, id asc fetch first 20 rows only
```

```
SELECT
    "public"."message_request"."id",
    "public"."message_request"."tenant_id",
    "public"."message_request"."client_app",
    "public"."message_request"."client_reference",
    "public"."message_request"."recipient_id",
    "public"."message_request"."channel",
    "public"."message_request"."category",
    "public"."message_request"."priority",
    "public"."message_request"."status",
    "public"."message_request"."status_reason",
    "public"."message_request"."template_code",
    "public"."message_request"."template_version_no",
    "public"."message_request"."template_locale",
    "public"."message_request"."payload_json",
    "public"."message_request"."options_json",
    "public"."message_request"."metadata_json",
    "public"."message_request"."fallback_channels",
    "public"."message_request"."fallback_index",
    "public"."message_request"."correlation_id",
    "public"."message_request"."idempotency_key",
    "public"."message_request"."request_hash",
    "public"."message_request"."scheduled_at",
    "public"."message_request"."expires_at",
    "public"."message_request"."attempt_count",
    "public"."message_request"."max_attempts",
    "public"."message_request"."next_attempt_at",
    "public"."message_request"."channel_used",
    "public"."message_request"."locked_until",
    "public"."message_request"."dlq_reason",
    "public"."message_request"."discarded",
    "public"."message_request"."bulk_job_id",
    "public"."message_request"."status_changed_at",
    "public"."message_request"."created_at",
    "public"."message_request"."updated_at",
    "public"."message_request"."version"
FROM "public"."message_request"
    /* public.idx_msg_created */
    /* scanCount: 21 */
ORDER BY 33 DESC, 1
FETCH FIRST 20 ROWS ONLY
/* index sorted: 1 of 2 columns */
```

Result: index access, scanCount=21

## Message list: by correlation id

- Code path: `MessageRequestRepository (correlationId filter)`
- Expected access path: idx_msg_correlation

```sql
select * from message_request where tenant_id = 'tenant-retail' and correlation_id = 'corr-5' fetch first 20 rows only
```

```
SELECT
    "public"."message_request"."id",
    "public"."message_request"."tenant_id",
    "public"."message_request"."client_app",
    "public"."message_request"."client_reference",
    "public"."message_request"."recipient_id",
    "public"."message_request"."channel",
    "public"."message_request"."category",
    "public"."message_request"."priority",
    "public"."message_request"."status",
    "public"."message_request"."status_reason",
    "public"."message_request"."template_code",
    "public"."message_request"."template_version_no",
    "public"."message_request"."template_locale",
    "public"."message_request"."payload_json",
    "public"."message_request"."options_json",
    "public"."message_request"."metadata_json",
    "public"."message_request"."fallback_channels",
    "public"."message_request"."fallback_index",
    "public"."message_request"."correlation_id",
    "public"."message_request"."idempotency_key",
    "public"."message_request"."request_hash",
    "public"."message_request"."scheduled_at",
    "public"."message_request"."expires_at",
    "public"."message_request"."attempt_count",
    "public"."message_request"."max_attempts",
    "public"."message_request"."next_attempt_at",
    "public"."message_request"."channel_used",
    "public"."message_request"."locked_until",
    "public"."message_request"."dlq_reason",
    "public"."message_request"."discarded",
    "public"."message_request"."bulk_job_id",
    "public"."message_request"."status_changed_at",
    "public"."message_request"."created_at",
    "public"."message_request"."updated_at",
    "public"."message_request"."version"
FROM "public"."message_request"
    /* public.idx_msg_correlation: correlation_id = 'corr-5' */
    /* scanCount: 2 */
WHERE ("tenant_id" = 'tenant-retail')
    AND ("correlation_id" = 'corr-5')
FETCH FIRST 20 ROWS ONLY
```

Result: index access, scanCount=2

## Outbox poll: due NEW rows, oldest first (OutboxPublisher, every 200 ms)

- Code path: `OutboxEventRepository.findByStatusAndAvailableAtLessThanEqualOrderByCreatedAtAsc`
- Expected access path: idx_outbox_status_available

```sql
select * from outbox_event where status = 'NEW' and available_at <= timestamp '2026-10-18 12:00:00' order by created_at asc fetch first 100 rows only
```

```
SELECT
    "public"."outbox_event"."id",
    "public"."outbox_event"."aggregate_id",
    "public"."outbox_event"."topic",
    "public"."outbox_event"."payload",
    "public"."outbox_event"."status",
    "public"."outbox_event"."available_at",
    "public"."outbox_event"."created_at",
    "public"."outbox_event"."sent_at"
FROM "public"."outbox_event"
    /* public.idx_outbox_status_available: status = 'NEW'
        AND available_at <= TIMESTAMP '2026-10-18 12:00:00'
     */
    /* scanCount: 1001 */
WHERE ("status" = 'NEW')
    AND ("available_at" <= TIMESTAMP '2026-10-18 12:00:00')
ORDER BY 7
FETCH FIRST 100 ROWS ONLY
```

Result: index access, scanCount=1001

## Due-retry poll (ScheduledDispatchPoller.pollRetries)

- Code path: `MessageRequestRepository.findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc`
- Expected access path: idx_msg_status_next_attempt

```sql
select * from message_request where status = 'RETRY_PENDING' and next_attempt_at <= timestamp '2026-10-18 12:00:00' order by next_attempt_at asc fetch first 100 rows only
```

```
SELECT
    "public"."message_request"."id",
    "public"."message_request"."tenant_id",
    "public"."message_request"."client_app",
    "public"."message_request"."client_reference",
    "public"."message_request"."recipient_id",
    "public"."message_request"."channel",
    "public"."message_request"."category",
    "public"."message_request"."priority",
    "public"."message_request"."status",
    "public"."message_request"."status_reason",
    "public"."message_request"."template_code",
    "public"."message_request"."template_version_no",
    "public"."message_request"."template_locale",
    "public"."message_request"."payload_json",
    "public"."message_request"."options_json",
    "public"."message_request"."metadata_json",
    "public"."message_request"."fallback_channels",
    "public"."message_request"."fallback_index",
    "public"."message_request"."correlation_id",
    "public"."message_request"."idempotency_key",
    "public"."message_request"."request_hash",
    "public"."message_request"."scheduled_at",
    "public"."message_request"."expires_at",
    "public"."message_request"."attempt_count",
    "public"."message_request"."max_attempts",
    "public"."message_request"."next_attempt_at",
    "public"."message_request"."channel_used",
    "public"."message_request"."locked_until",
    "public"."message_request"."dlq_reason",
    "public"."message_request"."discarded",
    "public"."message_request"."bulk_job_id",
    "public"."message_request"."status_changed_at",
    "public"."message_request"."created_at",
    "public"."message_request"."updated_at",
    "public"."message_request"."version"
FROM "public"."message_request"
    /* public.idx_msg_status_next_attempt: status = 'RETRY_PENDING'
        AND next_attempt_at <= TIMESTAMP '2026-10-18 12:00:00'
     */
    /* scanCount: 1001 */
WHERE ("status" = 'RETRY_PENDING')
    AND ("next_attempt_at" <= TIMESTAMP '2026-10-18 12:00:00')
ORDER BY 26
FETCH FIRST 100 ROWS ONLY
```

Result: index access, scanCount=1001

## Stale QUEUED poll (ScheduledDispatchPoller.pollStaleQueued)

- Code path: `MessageRequestRepository.findByStatusAndStatusChangedAtBefore`
- Expected access path: idx_msg_status_changed

```sql
select * from message_request where status = 'QUEUED' and status_changed_at < timestamp '2026-10-18 12:00:00' fetch first 100 rows only
```

```
SELECT
    "public"."message_request"."id",
    "public"."message_request"."tenant_id",
    "public"."message_request"."client_app",
    "public"."message_request"."client_reference",
    "public"."message_request"."recipient_id",
    "public"."message_request"."channel",
    "public"."message_request"."category",
    "public"."message_request"."priority",
    "public"."message_request"."status",
    "public"."message_request"."status_reason",
    "public"."message_request"."template_code",
    "public"."message_request"."template_version_no",
    "public"."message_request"."template_locale",
    "public"."message_request"."payload_json",
    "public"."message_request"."options_json",
    "public"."message_request"."metadata_json",
    "public"."message_request"."fallback_channels",
    "public"."message_request"."fallback_index",
    "public"."message_request"."correlation_id",
    "public"."message_request"."idempotency_key",
    "public"."message_request"."request_hash",
    "public"."message_request"."scheduled_at",
    "public"."message_request"."expires_at",
    "public"."message_request"."attempt_count",
    "public"."message_request"."max_attempts",
    "public"."message_request"."next_attempt_at",
    "public"."message_request"."channel_used",
    "public"."message_request"."locked_until",
    "public"."message_request"."dlq_reason",
    "public"."message_request"."discarded",
    "public"."message_request"."bulk_job_id",
    "public"."message_request"."status_changed_at",
    "public"."message_request"."created_at",
    "public"."message_request"."updated_at",
    "public"."message_request"."version"
FROM "public"."message_request"
    /* public.idx_msg_status_changed: status = 'QUEUED'
        AND status_changed_at < TIMESTAMP '2026-10-18 12:00:00'
     */
    /* scanCount: 100 */
WHERE ("status" = 'QUEUED')
    AND ("status_changed_at" < TIMESTAMP '2026-10-18 12:00:00')
FETCH FIRST 100 ROWS ONLY
```

Result: index access, scanCount=100

## Delivery-event dedupe lookup (callback processing)

- Code path: `DeliveryEventRepository.findByProviderIdAndProviderEventId`
- Expected access path: uq_delivery_event

```sql
select * from delivery_event where provider_id = 'simulated-email' and provider_event_id = 'pe-1'
```

```
SELECT
    "public"."delivery_event"."id",
    "public"."delivery_event"."message_id",
    "public"."delivery_event"."provider_id",
    "public"."delivery_event"."provider_event_id",
    "public"."delivery_event"."type",
    "public"."delivery_event"."occurred_at",
    "public"."delivery_event"."received_at",
    "public"."delivery_event"."applied",
    "public"."delivery_event"."ignored_reason",
    "public"."delivery_event"."raw_ref"
FROM "public"."delivery_event"
    /* public.uq_delivery_event_INDEX_A: provider_id = 'simulated-email'
        AND provider_event_id = 'pe-1'
     */
    /* scanCount: 2 */
WHERE ("provider_id" = 'simulated-email')
    AND ("provider_event_id" = 'pe-1')
```

Result: index access, scanCount=2

## Delivery events of one message (timeline)

- Code path: `DeliveryEventRepository.findByMessageIdOrderByOccurredAtAsc`
- Expected access path: idx_event_message

```sql
select * from delivery_event where message_id = 'qp-10071' order by occurred_at asc
```

```
SELECT
    "public"."delivery_event"."id",
    "public"."delivery_event"."message_id",
    "public"."delivery_event"."provider_id",
    "public"."delivery_event"."provider_event_id",
    "public"."delivery_event"."type",
    "public"."delivery_event"."occurred_at",
    "public"."delivery_event"."received_at",
    "public"."delivery_event"."applied",
    "public"."delivery_event"."ignored_reason",
    "public"."delivery_event"."raw_ref"
FROM "public"."delivery_event"
    /* public.fk_event_message_INDEX_A: message_id = 'qp-10071' */
    /* scanCount: 2 */
WHERE "message_id" = 'qp-10071'
ORDER BY 6
```

Result: index access, scanCount=2

## Audit events of one entity (history, ordered)

- Code path: `AuditEventRepository.findByEntityTypeAndEntityIdOrderByOccurredAtAsc`
- Expected access path: idx_audit_entity

```sql
select * from audit_event where entity_type = 'MESSAGE' and entity_id = 'qp-10071' order by occurred_at asc
```

```
SELECT
    "public"."audit_event"."id",
    "public"."audit_event"."tenant_id",
    "public"."audit_event"."actor",
    "public"."audit_event"."actor_role",
    "public"."audit_event"."action",
    "public"."audit_event"."entity_type",
    "public"."audit_event"."entity_id",
    "public"."audit_event"."correlation_id",
    "public"."audit_event"."detail_json",
    "public"."audit_event"."occurred_at"
FROM "public"."audit_event"
    /* public.idx_audit_entity: entity_type = 'MESSAGE'
        AND entity_id = 'qp-10071'
     */
    /* scanCount: 3 */
WHERE ("entity_type" = 'MESSAGE')
    AND ("entity_id" = 'qp-10071')
ORDER BY 10
```

Result: index access, scanCount=3

## Audit search: tenant + time window

- Code path: `AuditService.search`
- Expected access path: idx_audit_tenant_time; same H2 sort limitation as the message list (tie-breaker column id)

```sql
select * from audit_event where tenant_id = 'tenant-retail' and occurred_at >= timestamp '2026-10-17 00:00:00' order by occurred_at desc, id asc fetch first 50 rows only
```

```
SELECT
    "public"."audit_event"."id",
    "public"."audit_event"."tenant_id",
    "public"."audit_event"."actor",
    "public"."audit_event"."actor_role",
    "public"."audit_event"."action",
    "public"."audit_event"."entity_type",
    "public"."audit_event"."entity_id",
    "public"."audit_event"."correlation_id",
    "public"."audit_event"."detail_json",
    "public"."audit_event"."occurred_at"
FROM "public"."audit_event"
    /* public.idx_audit_tenant_time: tenant_id = 'tenant-retail'
        AND occurred_at >= TIMESTAMP '2026-10-17 00:00:00'
     */
    /* scanCount: 100801 */
WHERE ("tenant_id" = 'tenant-retail')
    AND ("occurred_at" >= TIMESTAMP '2026-10-17 00:00:00')
ORDER BY 10 DESC, 1
FETCH FIRST 50 ROWS ONLY
```

Result: index access, scanCount=100801

## Callback lookup: provider message id -> attempt

- Code path: `MessageAttemptRepository.findFirstByProviderIdAndProviderMessageId`
- Expected access path: idx_attempt_provider_msg

```sql
select * from message_attempt where provider_id = 'simulated-email' and provider_message_id = 'em-1'
```

```
SELECT
    "public"."message_attempt"."id",
    "public"."message_attempt"."message_id",
    "public"."message_attempt"."attempt_no",
    "public"."message_attempt"."channel",
    "public"."message_attempt"."provider_id",
    "public"."message_attempt"."state",
    "public"."message_attempt"."outcome",
    "public"."message_attempt"."provider_message_id",
    "public"."message_attempt"."error_code",
    "public"."message_attempt"."latency_ms",
    "public"."message_attempt"."started_at",
    "public"."message_attempt"."finished_at"
FROM "public"."message_attempt"
    /* public.idx_attempt_provider_msg: provider_id = 'simulated-email'
        AND provider_message_id = 'em-1'
     */
    /* scanCount: 2 */
WHERE ("provider_id" = 'simulated-email')
    AND ("provider_message_id" = 'em-1')
```

Result: index access, scanCount=2

## Idempotency lookup (tenant, key)

- Code path: `IdempotencyRecordRepository.findByTenantIdAndIdemKey`
- Expected access path: uq_msg_idempotency

```sql
select * from idempotency_record where tenant_id = 'tenant-retail' and idem_key = 'idem-777'
```

```
SELECT
    "public"."idempotency_record"."id",
    "public"."idempotency_record"."tenant_id",
    "public"."idempotency_record"."idem_key",
    "public"."idempotency_record"."request_hash",
    "public"."idempotency_record"."status",
    "public"."idempotency_record"."response_status",
    "public"."idempotency_record"."response_body",
    "public"."idempotency_record"."message_id",
    "public"."idempotency_record"."created_at",
    "public"."idempotency_record"."expires_at"
FROM "public"."idempotency_record"
    /* public.uq_idempotency_INDEX_A: tenant_id = 'tenant-retail'
        AND idem_key = 'idem-777'
     */
    /* scanCount: 2 */
WHERE ("tenant_id" = 'tenant-retail')
    AND ("idem_key" = 'idem-777')
```

Result: index access, scanCount=2

## Consumer dedupe (processed_message by dispatch id)

- Code path: `ProcessedMessageRepository.existsByDispatchId`
- Expected access path: uq_processed_dispatch

```sql
select 1 from processed_message where dispatch_id = 'qp-10071:1'
```

```
SELECT
    1
FROM "public"."processed_message"
    /* public.uq_processed_dispatch_INDEX_8: dispatch_id = 'qp-10071:1' */
    /* scanCount: 2 */
WHERE "dispatch_id" = 'qp-10071:1'
```

Result: index access, scanCount=2

## Dashboard: counts by status over the last 24 h (all tenants)

- Code path: `MessageRequestRepository.countByStatusSince`
- Expected access path: aggregate over a time range: reads every row of the window by design (idx_msg_created); cost grows with the window, not the table

```sql
select status, count(*) from message_request where created_at >= timestamp '2026-10-17 12:00:00' group by status
```

```
SELECT
    "status",
    COUNT(*)
FROM "public"."message_request"
    /* public.idx_msg_created: created_at >= TIMESTAMP '2026-10-17 12:00:00' */
    /* scanCount: 3313 */
WHERE "created_at" >= TIMESTAMP '2026-10-17 12:00:00'
GROUP BY "status"
```

Result: index access, scanCount=3313

## Retention scan: oldest terminal messages (RetentionJob)

- Code path: `RetentionJob.purgeMessages`
- Expected access path: H2 picks the status index and sorts (nightly batch job); PostgreSQL planner expected to differ - not verified

```sql
select id from message_request m where m.created_at < timestamp '2026-09-20 00:00:00' and m.updated_at < timestamp '2026-09-20 00:00:00' and m.status in ('DELIVERED','REJECTED','CANCELLED','FAILED') order by m.created_at fetch first 500 rows only
```

```
SELECT
    "id"
FROM "public"."message_request" "m"
    /* public.idx_msg_status_next_attempt: status IN('DELIVERED', 'REJECTED', 'CANCELLED', 'FAILED') */
    /* scanCount: 28001 */
WHERE ("m"."status" IN('DELIVERED', 'REJECTED', 'CANCELLED', 'FAILED'))
    AND ("m"."created_at" < TIMESTAMP '2026-09-20 00:00:00')
    AND ("m"."updated_at" < TIMESTAMP '2026-09-20 00:00:00')
ORDER BY "m"."created_at"
FETCH FIRST 500 ROWS ONLY
```

Result: index access, scanCount=28001

