# Entity-Relationship Diagram

Derived from CONTRACT section 4. Column lists show the keys and the columns that matter for relationships and constraints; the migration under `backend/src/main/resources/db/migration` is authoritative (see source). Logical references to `message_request`, `recipient`, etc. are foreign keys; `outbox_event.aggregate_id` and `processed_message.dispatch_id` are logical references only.

```mermaid
erDiagram
  tenant ||--o{ recipient : owns
  tenant ||--o{ template : owns
  tenant ||--o{ message_request : owns
  tenant ||--o{ suppression : scopes
  tenant ||--o{ bulk_job : owns
  tenant ||--o{ audit_event : scopes
  tenant ||--o{ idempotency_record : scopes
  recipient ||--o| recipient_preference : has
  recipient ||--o{ message_request : receives
  template ||--o{ template_version : has
  message_request ||--o{ message_attempt : has
  message_request ||--o{ delivery_event : receives
  message_request }o--o| bulk_job : "bulk_job_id"
  message_request }o--o| template_version : "pinned (code, version_no, locale)"
  bulk_job ||--o{ bulk_job_failure : records
  provider_config ||--o{ message_attempt : "provider_id"
  provider_config ||--o{ delivery_event : "provider_id"
  message_request ||--o{ outbox_event : "aggregate_id"
  message_request ||--o{ idempotency_record : "message_id"

  tenant { varchar id PK
    varchar name
    varchar status }
  recipient { varchar id PK
    varchar tenant_id FK
    varchar external_ref "UNIQUE per tenant"
    varchar email
    varchar phone_e164
    varchar push_token
    varchar locale
    varchar time_zone
    boolean deleted
    bigint version }
  recipient_preference { varchar recipient_id PK
    boolean marketing_opt_in
    boolean email_enabled
    boolean sms_enabled
    boolean push_enabled
    varchar quiet_start
    varchar quiet_end
    varchar fallback_order
    bigint version }
  suppression { varchar id PK
    varchar tenant_id
    varchar channel
    varchar address_hash
    varchar reason "UNIQUE tenant_id channel address_hash" }
  template { varchar id PK
    varchar tenant_id FK
    varchar code
    varchar channel
    varchar category "UNIQUE tenant_id code channel" }
  template_version { varchar id PK
    varchar template_id FK
    int version_no
    varchar locale
    varchar status
    varchar subject
    varchar body
    varchar variables_json
    varchar created_by
    varchar approved_by
    timestamp published_at
    bigint version "UNIQUE template_id version_no locale" }
  message_request { varchar id PK
    varchar tenant_id FK
    varchar client_app
    varchar recipient_id FK
    varchar channel
    varchar category
    varchar priority
    varchar status
    varchar template_code
    int template_version_no
    varchar template_locale
    varchar payload_json
    varchar correlation_id
    varchar idempotency_key "UNIQUE tenant_id idempotency_key"
    varchar request_hash
    timestamp scheduled_at
    timestamp expires_at
    int attempt_count
    int max_attempts
    timestamp next_attempt_at
    varchar channel_used
    varchar status_reason
    varchar bulk_job_id FK
    bigint version }
  message_attempt { varchar id PK
    varchar message_id FK
    int attempt_no "UNIQUE message_id attempt_no"
    varchar channel
    varchar provider_id
    varchar outcome
    varchar provider_message_id
    varchar error_code
    bigint latency_ms
    timestamp started_at
    timestamp finished_at }
  provider_config { varchar id PK
    varchar provider_id "UNIQUE"
    varchar channel
    boolean enabled
    int priority_order
    int rate_limit_per_sec
    int timeout_ms
    varchar hmac_key_ref }
  delivery_event { varchar id PK
    varchar message_id FK
    varchar provider_id
    varchar provider_event_id "UNIQUE provider_id provider_event_id"
    varchar type
    timestamp occurred_at
    timestamp received_at
    boolean applied
    varchar ignored_reason
    varchar raw_ref }
  bulk_job { varchar id PK
    varchar tenant_id FK
    varchar name
    varchar status
    bigint total
    bigint processed
    bigint succeeded
    bigint failed
    bigint duplicate
    varchar file_name
    int rate_limit_per_sec
    varchar created_by
    timestamp started_at
    timestamp finished_at
    bigint spring_batch_execution_id
    bigint version }
  bulk_job_failure { varchar job_id FK
    int line_no
    varchar reason
    varchar raw_line_hash }
  audit_event { varchar id PK
    varchar tenant_id
    varchar actor
    varchar actor_role
    varchar action
    varchar entity_type
    varchar entity_id
    varchar correlation_id
    varchar detail_json
    timestamp occurred_at }
  idempotency_record { varchar id PK
    varchar tenant_id
    varchar idem_key "UNIQUE tenant_id idem_key"
    varchar request_hash
    varchar status "IN_PROGRESS or COMPLETED"
    int response_status
    varchar response_body
    varchar message_id
    timestamp expires_at }
  processed_message { varchar dispatch_id PK "UNIQUE" }
  outbox_event { varchar id PK
    varchar aggregate_id
    varchar topic
    varchar payload
    varchar status "NEW or SENT"
    timestamp created_at
    timestamp sent_at }
  replay_guard { varchar event_id PK
    timestamp seen_at }
```

Not drawn: Spring Batch metadata tables (`BATCH_JOB_INSTANCE`, `BATCH_JOB_EXECUTION`, `BATCH_STEP_EXECUTION`, ... created by `V100__spring_batch.sql`); `bulk_job.spring_batch_execution_id` references `BATCH_JOB_EXECUTION` logically.

Indexes (CONTRACT 4) are discussed in `docs/database/query-plans-and-indexes.md`.
