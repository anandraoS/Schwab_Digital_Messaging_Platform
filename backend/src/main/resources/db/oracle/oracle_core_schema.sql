-- ============================================================================
-- Oracle 19c+ REFERENCE DDL for the core tables (see docs/database/oracle-mapping.md).
-- NOT executed in this repository: the platform runs on PostgreSQL 16 (ADR-0005) and H2 in PostgreSQL mode.
-- This file is not on any Flyway location, so it can never be applied by accident.
--
-- Mapping rules applied here
--   VARCHAR(n)              -> VARCHAR2(n CHAR)       (character semantics, same limits as the portable DDL)
--   VARCHAR(4000+) / large  -> CLOB                   (Oracle VARCHAR2 is limited to 4000 bytes in standard mode)
--   BOOLEAN                 -> NUMBER(1) CHECK (IN (0,1))
--   BIGINT                  -> NUMBER(19)
--   INTEGER                 -> NUMBER(10)
--   TIMESTAMP               -> TIMESTAMP(6) (stored in UTC; the application writes Instants)
--   TIME                    -> VARCHAR2(8 CHAR) 'HH24:MI:SS' (Oracle has no TIME type)
--   column "type"           -> EVENT_TYPE            (TYPE is a keyword)
--   Oracle treats '' as NULL: the application never relies on empty strings.
--   Spring Batch metadata   -> use the schema-oracle.sql shipped in spring-batch-core.
--   Hibernate               -> set hibernate.dialect=org.hibernate.dialect.OracleDialect and
--                              spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP.
--   Enum columns            -> VARCHAR2 with CHECK constraints (optional, shown for message_request.status).
-- ============================================================================

CREATE TABLE tenant (
    id          VARCHAR2(36 CHAR)  NOT NULL,
    name        VARCHAR2(200 CHAR) NOT NULL,
    status      VARCHAR2(20 CHAR)  NOT NULL,
    created_at  TIMESTAMP(6)       NOT NULL,
    updated_at  TIMESTAMP(6)       NOT NULL,
    CONSTRAINT pk_tenant PRIMARY KEY (id)
);

CREATE TABLE recipient (
    id            VARCHAR2(36 CHAR)  NOT NULL,
    tenant_id     VARCHAR2(36 CHAR)  NOT NULL,
    external_ref  VARCHAR2(100 CHAR) NOT NULL,
    display_name  VARCHAR2(200 CHAR),
    email         VARCHAR2(320 CHAR),
    phone_e164    VARCHAR2(20 CHAR),
    push_token    VARCHAR2(255 CHAR),
    locale        VARCHAR2(20 CHAR)  NOT NULL,
    time_zone     VARCHAR2(50 CHAR)  NOT NULL,
    deleted       NUMBER(1)          DEFAULT 0 NOT NULL CHECK (deleted IN (0, 1)),
    created_at    TIMESTAMP(6)       NOT NULL,
    updated_at    TIMESTAMP(6)       NOT NULL,
    version       NUMBER(19)         DEFAULT 0 NOT NULL,
    CONSTRAINT pk_recipient PRIMARY KEY (id),
    CONSTRAINT fk_recipient_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT uq_recipient_external UNIQUE (tenant_id, external_ref)
);
CREATE INDEX idx_recipient_tenant_name ON recipient (tenant_id, display_name);

CREATE TABLE recipient_preference (
    recipient_id      VARCHAR2(36 CHAR) NOT NULL,
    marketing_opt_in  NUMBER(1)         DEFAULT 0 NOT NULL CHECK (marketing_opt_in IN (0, 1)),
    email_enabled     NUMBER(1)         DEFAULT 1 NOT NULL CHECK (email_enabled IN (0, 1)),
    sms_enabled       NUMBER(1)         DEFAULT 1 NOT NULL CHECK (sms_enabled IN (0, 1)),
    push_enabled      NUMBER(1)         DEFAULT 1 NOT NULL CHECK (push_enabled IN (0, 1)),
    quiet_start       VARCHAR2(8 CHAR),
    quiet_end         VARCHAR2(8 CHAR),
    fallback_order    VARCHAR2(100 CHAR),
    created_at        TIMESTAMP(6)      NOT NULL,
    updated_at        TIMESTAMP(6)      NOT NULL,
    version           NUMBER(19)        DEFAULT 0 NOT NULL,
    CONSTRAINT pk_recipient_preference PRIMARY KEY (recipient_id),
    CONSTRAINT fk_pref_recipient FOREIGN KEY (recipient_id) REFERENCES recipient (id)
);

CREATE TABLE suppression (
    id            VARCHAR2(36 CHAR)  NOT NULL,
    tenant_id     VARCHAR2(36 CHAR)  NOT NULL,
    channel       VARCHAR2(10 CHAR)  NOT NULL,
    address_hash  VARCHAR2(64 CHAR)  NOT NULL,
    reason        VARCHAR2(100 CHAR) NOT NULL,
    created_at    TIMESTAMP(6)       NOT NULL,
    CONSTRAINT pk_suppression PRIMARY KEY (id),
    CONSTRAINT uq_suppression UNIQUE (tenant_id, channel, address_hash)
);

CREATE TABLE template (
    id          VARCHAR2(36 CHAR)  NOT NULL,
    tenant_id   VARCHAR2(36 CHAR)  NOT NULL,
    code        VARCHAR2(100 CHAR) NOT NULL,
    name        VARCHAR2(200 CHAR) NOT NULL,
    channel     VARCHAR2(10 CHAR)  NOT NULL,
    category    VARCHAR2(30 CHAR)  NOT NULL,
    created_at  TIMESTAMP(6)       NOT NULL,
    updated_at  TIMESTAMP(6)       NOT NULL,
    CONSTRAINT pk_template PRIMARY KEY (id),
    CONSTRAINT fk_template_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT uq_template_code UNIQUE (tenant_id, code, channel)
);

CREATE TABLE template_version (
    id                 VARCHAR2(36 CHAR)  NOT NULL,
    template_id        VARCHAR2(36 CHAR)  NOT NULL,
    version_no         NUMBER(10)         NOT NULL,
    locale             VARCHAR2(20 CHAR)  NOT NULL,
    status             VARCHAR2(30 CHAR)  NOT NULL,
    subject            VARCHAR2(500 CHAR),
    body               CLOB               NOT NULL,
    variables_json     VARCHAR2(2000 CHAR) NOT NULL,
    created_by         VARCHAR2(200 CHAR) NOT NULL,
    approved_by        VARCHAR2(200 CHAR),
    published_at       TIMESTAMP(6),
    rejection_comment  VARCHAR2(1000 CHAR),
    created_at         TIMESTAMP(6)       NOT NULL,
    updated_at         TIMESTAMP(6)       NOT NULL,
    version            NUMBER(19)         DEFAULT 0 NOT NULL,
    CONSTRAINT pk_template_version PRIMARY KEY (id),
    CONSTRAINT fk_tv_template FOREIGN KEY (template_id) REFERENCES template (id),
    CONSTRAINT uq_template_version UNIQUE (template_id, version_no, locale),
    CONSTRAINT ck_tv_status CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'PUBLISHED', 'RETIRED'))
);
CREATE INDEX idx_tv_template_status ON template_version (template_id, status);

CREATE TABLE message_request (
    id                   VARCHAR2(36 CHAR)   NOT NULL,
    tenant_id            VARCHAR2(36 CHAR)   NOT NULL,
    client_app           VARCHAR2(100 CHAR),
    client_reference     VARCHAR2(200 CHAR),
    recipient_id         VARCHAR2(36 CHAR)   NOT NULL,
    channel              VARCHAR2(10 CHAR)   NOT NULL,
    category             VARCHAR2(30 CHAR)   NOT NULL,
    priority             VARCHAR2(10 CHAR)   NOT NULL,
    status               VARCHAR2(30 CHAR)   NOT NULL,
    status_reason        VARCHAR2(500 CHAR),
    template_code        VARCHAR2(100 CHAR)  NOT NULL,
    template_version_no  NUMBER(10)          NOT NULL,
    template_locale      VARCHAR2(20 CHAR)   NOT NULL,
    payload_json         CLOB                NOT NULL,
    options_json         VARCHAR2(2000 CHAR),
    metadata_json        VARCHAR2(2000 CHAR),
    fallback_channels    VARCHAR2(100 CHAR),
    fallback_index       NUMBER(10)          DEFAULT 0 NOT NULL,
    correlation_id       VARCHAR2(64 CHAR)   NOT NULL,
    idempotency_key      VARCHAR2(128 CHAR),
    request_hash         VARCHAR2(64 CHAR),
    scheduled_at         TIMESTAMP(6),
    expires_at           TIMESTAMP(6),
    attempt_count        NUMBER(10)          DEFAULT 0 NOT NULL,
    max_attempts         NUMBER(10)          DEFAULT 5 NOT NULL,
    next_attempt_at      TIMESTAMP(6),
    channel_used         VARCHAR2(10 CHAR),
    locked_until         TIMESTAMP(6),
    dlq_reason           VARCHAR2(30 CHAR),
    discarded            NUMBER(1)           DEFAULT 0 NOT NULL CHECK (discarded IN (0, 1)),
    bulk_job_id          VARCHAR2(36 CHAR),
    status_changed_at    TIMESTAMP(6)        NOT NULL,
    created_at           TIMESTAMP(6)        NOT NULL,
    updated_at           TIMESTAMP(6)        NOT NULL,
    version              NUMBER(19)          DEFAULT 0 NOT NULL,
    CONSTRAINT pk_message_request PRIMARY KEY (id),
    CONSTRAINT fk_msg_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_msg_recipient FOREIGN KEY (recipient_id) REFERENCES recipient (id),
    CONSTRAINT uq_msg_idempotency UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT ck_msg_status CHECK (status IN ('ACCEPTED', 'VALIDATED', 'QUEUED', 'PROCESSING', 'SENT_TO_PROVIDER',
        'DELIVERED', 'REJECTED', 'RETRY_PENDING', 'FAILED', 'DEAD_LETTERED', 'CANCELLED', 'DELIVERY_UNKNOWN'))
);
CREATE INDEX idx_msg_tenant_status_created ON message_request (tenant_id, status, created_at DESC);
CREATE INDEX idx_msg_recipient_created ON message_request (recipient_id, created_at);
CREATE INDEX idx_msg_status_next_attempt ON message_request (status, next_attempt_at);
CREATE INDEX idx_msg_status_scheduled ON message_request (status, scheduled_at);
CREATE INDEX idx_msg_correlation ON message_request (correlation_id);
CREATE INDEX idx_msg_bulk_job ON message_request (bulk_job_id);
CREATE INDEX idx_msg_client_reference ON message_request (tenant_id, client_reference);
-- Retention at volume: range-partition message_request by created_at (monthly, interval partitioning) and drop
-- partitions older than messaging.retention.message-days instead of running large DELETEs.

CREATE TABLE message_attempt (
    id                   VARCHAR2(36 CHAR)  NOT NULL,
    message_id           VARCHAR2(36 CHAR)  NOT NULL,
    attempt_no           NUMBER(10)         NOT NULL,
    channel              VARCHAR2(10 CHAR)  NOT NULL,
    provider_id          VARCHAR2(100 CHAR),
    state                VARCHAR2(20 CHAR)  NOT NULL,
    outcome              VARCHAR2(30 CHAR),
    provider_message_id  VARCHAR2(200 CHAR),
    error_code           VARCHAR2(100 CHAR),
    latency_ms           NUMBER(19),
    started_at           TIMESTAMP(6)       NOT NULL,
    finished_at          TIMESTAMP(6),
    CONSTRAINT pk_message_attempt PRIMARY KEY (id),
    CONSTRAINT fk_attempt_message FOREIGN KEY (message_id) REFERENCES message_request (id),
    CONSTRAINT uq_attempt UNIQUE (message_id, attempt_no)
);

CREATE TABLE delivery_event (
    id                 VARCHAR2(36 CHAR)  NOT NULL,
    message_id         VARCHAR2(36 CHAR)  NOT NULL,
    provider_id        VARCHAR2(100 CHAR) NOT NULL,
    provider_event_id  VARCHAR2(200 CHAR) NOT NULL,
    event_type         VARCHAR2(30 CHAR)  NOT NULL,
    occurred_at        TIMESTAMP(6)       NOT NULL,
    received_at        TIMESTAMP(6)       NOT NULL,
    applied            NUMBER(1)          NOT NULL CHECK (applied IN (0, 1)),
    ignored_reason     VARCHAR2(30 CHAR),
    raw_ref            VARCHAR2(100 CHAR),
    CONSTRAINT pk_delivery_event PRIMARY KEY (id),
    CONSTRAINT fk_event_message FOREIGN KEY (message_id) REFERENCES message_request (id),
    CONSTRAINT uq_delivery_event UNIQUE (provider_id, provider_event_id)
);
CREATE INDEX idx_event_message ON delivery_event (message_id, occurred_at);
CREATE INDEX idx_event_type_received ON delivery_event (event_type, received_at);

CREATE TABLE audit_event (
    id              VARCHAR2(36 CHAR)  NOT NULL,
    tenant_id       VARCHAR2(36 CHAR),
    actor           VARCHAR2(200 CHAR) NOT NULL,
    actor_role      VARCHAR2(500 CHAR),
    action          VARCHAR2(60 CHAR)  NOT NULL,
    entity_type     VARCHAR2(40 CHAR)  NOT NULL,
    entity_id       VARCHAR2(100 CHAR) NOT NULL,
    correlation_id  VARCHAR2(64 CHAR),
    detail_json     CLOB,
    occurred_at     TIMESTAMP(6)       NOT NULL,
    CONSTRAINT pk_audit_event PRIMARY KEY (id)
);
CREATE INDEX idx_audit_entity ON audit_event (entity_type, entity_id, occurred_at);
CREATE INDEX idx_audit_tenant_time ON audit_event (tenant_id, occurred_at);
-- Append-only: grant the application account INSERT and SELECT only; no UPDATE or DELETE on audit_event.

CREATE TABLE idempotency_record (
    id               VARCHAR2(36 CHAR)  NOT NULL,
    tenant_id        VARCHAR2(36 CHAR)  NOT NULL,
    idem_key         VARCHAR2(128 CHAR) NOT NULL,
    request_hash     VARCHAR2(64 CHAR)  NOT NULL,
    status           VARCHAR2(20 CHAR)  NOT NULL,
    response_status  NUMBER(10),
    response_body    CLOB,
    message_id       VARCHAR2(36 CHAR),
    created_at       TIMESTAMP(6)       NOT NULL,
    expires_at       TIMESTAMP(6)       NOT NULL,
    CONSTRAINT pk_idempotency_record PRIMARY KEY (id),
    CONSTRAINT uq_idempotency UNIQUE (tenant_id, idem_key)
);
CREATE INDEX idx_idempotency_expires ON idempotency_record (expires_at);

CREATE TABLE outbox_event (
    id            VARCHAR2(36 CHAR)  NOT NULL,
    aggregate_id  VARCHAR2(36 CHAR)  NOT NULL,
    topic         VARCHAR2(100 CHAR) NOT NULL,
    payload       CLOB               NOT NULL,
    status        VARCHAR2(10 CHAR)  NOT NULL,
    available_at  TIMESTAMP(6)       NOT NULL,
    created_at    TIMESTAMP(6)       NOT NULL,
    sent_at       TIMESTAMP(6),
    CONSTRAINT pk_outbox_event PRIMARY KEY (id)
);
CREATE INDEX idx_outbox_status_available ON outbox_event (status, available_at);
-- Competing publishers: SELECT ... FOR UPDATE SKIP LOCKED is available on Oracle; the portable implementation uses
-- the compare-and-set UPDATE ... WHERE status = 'NEW' instead, which works unchanged.

-- Stage 2 dispatch pipeline indexes (mirror of db/migration/V50__dispatch_indexes.sql)
CREATE INDEX idx_attempt_provider_msg ON message_attempt (provider_id, provider_message_id);
CREATE INDEX idx_msg_status_locked ON message_request (status, locked_until);
CREATE INDEX idx_msg_status_changed ON message_request (status, status_changed_at);
CREATE INDEX idx_audit_entity_action ON audit_event (entity_type, entity_id, action, occurred_at);
CREATE INDEX idx_outbox_aggregate_status ON outbox_event (aggregate_id, status);

-- ---------------------------------------------------------------------------
-- Dispatch dedupe and callback replay memory (mirror of V4)
-- ---------------------------------------------------------------------------
CREATE TABLE processed_message (
    id            VARCHAR2(36 CHAR)  NOT NULL,
    dispatch_id   VARCHAR2(100 CHAR) NOT NULL,
    message_id    VARCHAR2(36 CHAR)  NOT NULL,
    processed_at  TIMESTAMP(6)       NOT NULL,
    CONSTRAINT pk_processed_message PRIMARY KEY (id),
    CONSTRAINT uq_processed_dispatch UNIQUE (dispatch_id)
);

CREATE TABLE replay_guard (
    event_id  VARCHAR2(200 CHAR) NOT NULL,
    seen_at   TIMESTAMP(6)       NOT NULL,
    CONSTRAINT pk_replay_guard PRIMARY KEY (event_id)
);

-- ---------------------------------------------------------------------------
-- Bulk jobs (mirror of V4 + V200)
-- ---------------------------------------------------------------------------
CREATE TABLE bulk_job (
    id                         VARCHAR2(36 CHAR)  NOT NULL,
    tenant_id                  VARCHAR2(36 CHAR)  NOT NULL,
    name                       VARCHAR2(200 CHAR) NOT NULL,
    status                     VARCHAR2(30 CHAR)  NOT NULL,
    template_code              VARCHAR2(100 CHAR),
    channel                    VARCHAR2(10 CHAR),
    category                   VARCHAR2(30 CHAR),
    total                      NUMBER(19)         DEFAULT 0 NOT NULL,
    processed                  NUMBER(19)         DEFAULT 0 NOT NULL,
    succeeded                  NUMBER(19)         DEFAULT 0 NOT NULL,
    failed                     NUMBER(19)         DEFAULT 0 NOT NULL,
    duplicate                  NUMBER(19)         DEFAULT 0 NOT NULL,
    file_name                  VARCHAR2(255 CHAR),
    rate_limit_per_sec         NUMBER(10),
    created_by                 VARCHAR2(200 CHAR),
    started_at                 TIMESTAMP(6),
    finished_at                TIMESTAMP(6),
    spring_batch_execution_id  NUMBER(19),
    created_at                 TIMESTAMP(6)       NOT NULL,
    updated_at                 TIMESTAMP(6)       NOT NULL,
    version                    NUMBER(19)         DEFAULT 0 NOT NULL,
    CONSTRAINT pk_bulk_job PRIMARY KEY (id),
    CONSTRAINT fk_bulk_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)
);
CREATE INDEX idx_bulk_tenant_created ON bulk_job (tenant_id, created_at);

CREATE TABLE bulk_job_failure (
    id             VARCHAR2(36 CHAR)  NOT NULL,
    job_id         VARCHAR2(36 CHAR)  NOT NULL,
    line_no        NUMBER(19)         NOT NULL,   -- 0 = job-level failure
    reason         VARCHAR2(500 CHAR) NOT NULL,
    raw_line_hash  VARCHAR2(64 CHAR),
    created_at     TIMESTAMP(6)       NOT NULL,
    CONSTRAINT pk_bulk_job_failure PRIMARY KEY (id),
    CONSTRAINT fk_bulk_failure_job FOREIGN KEY (job_id) REFERENCES bulk_job (id)
);
CREATE INDEX idx_bulk_failure_job ON bulk_job_failure (job_id, line_no);
-- At most one failure record per input line (idempotent failure recording across retries and restarts)
CREATE UNIQUE INDEX uq_bulk_failure_job_line ON bulk_job_failure (job_id, line_no);

CREATE TABLE bulk_job_row (
    job_id        VARCHAR2(36 CHAR)  NOT NULL,
    external_ref  VARCHAR2(100 CHAR) NOT NULL,
    line_no       NUMBER(19)         NOT NULL,
    created_at    TIMESTAMP(6)       NOT NULL,
    CONSTRAINT pk_bulk_job_row PRIMARY KEY (job_id, external_ref),
    CONSTRAINT fk_bulk_row_job FOREIGN KEY (job_id) REFERENCES bulk_job (id)
);

-- ---------------------------------------------------------------------------
-- Legal hold honoured by RetentionJob (mirror of V200). released_at NULL = active.
-- ---------------------------------------------------------------------------
CREATE TABLE legal_hold (
    id           VARCHAR2(36 CHAR)  NOT NULL,
    tenant_id    VARCHAR2(36 CHAR)  NOT NULL,
    scope        VARCHAR2(20 CHAR)  NOT NULL,
    entity_id    VARCHAR2(100 CHAR),
    reason       VARCHAR2(500 CHAR),
    created_by   VARCHAR2(200 CHAR),
    created_at   TIMESTAMP(6)       NOT NULL,
    released_at  TIMESTAMP(6),
    CONSTRAINT pk_legal_hold PRIMARY KEY (id)
);
CREATE INDEX idx_legal_hold_active ON legal_hold (tenant_id, scope, released_at);

-- Retention scans by age (mirror of V200)
CREATE INDEX idx_audit_occurred ON audit_event (occurred_at);
CREATE INDEX idx_msg_created ON message_request (created_at);
CREATE INDEX idx_event_received ON delivery_event (received_at);
CREATE INDEX idx_processed_at ON processed_message (processed_at);
CREATE INDEX idx_replay_seen ON replay_guard (seen_at);
