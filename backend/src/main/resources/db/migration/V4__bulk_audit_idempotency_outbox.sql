CREATE TABLE bulk_job (
    id                         VARCHAR(36)  NOT NULL,
    tenant_id                  VARCHAR(36)  NOT NULL,
    name                       VARCHAR(200) NOT NULL,
    status                     VARCHAR(30)  NOT NULL,
    template_code              VARCHAR(100),
    channel                    VARCHAR(10),
    category                   VARCHAR(30),
    total                      BIGINT       NOT NULL DEFAULT 0,
    processed                  BIGINT       NOT NULL DEFAULT 0,
    succeeded                  BIGINT       NOT NULL DEFAULT 0,
    failed                     BIGINT       NOT NULL DEFAULT 0,
    duplicate                  BIGINT       NOT NULL DEFAULT 0,
    file_name                  VARCHAR(255),
    rate_limit_per_sec         INTEGER,
    created_by                 VARCHAR(200),
    started_at                 TIMESTAMP,
    finished_at                TIMESTAMP,
    spring_batch_execution_id  BIGINT,
    created_at                 TIMESTAMP    NOT NULL,
    updated_at                 TIMESTAMP    NOT NULL,
    version                    BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_bulk_job PRIMARY KEY (id),
    CONSTRAINT fk_bulk_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)
);
CREATE INDEX idx_bulk_tenant_created ON bulk_job (tenant_id, created_at);

CREATE TABLE bulk_job_failure (
    id            VARCHAR(36)  NOT NULL,
    job_id        VARCHAR(36)  NOT NULL,
    line_no       BIGINT       NOT NULL,
    reason        VARCHAR(500) NOT NULL,
    raw_line_hash VARCHAR(64),
    created_at    TIMESTAMP    NOT NULL,
    CONSTRAINT pk_bulk_job_failure PRIMARY KEY (id),
    CONSTRAINT fk_bulk_failure_job FOREIGN KEY (job_id) REFERENCES bulk_job (id)
);
CREATE INDEX idx_bulk_failure_job ON bulk_job_failure (job_id, line_no);

CREATE TABLE audit_event (
    id              VARCHAR(36)   NOT NULL,
    tenant_id       VARCHAR(36),
    actor           VARCHAR(200)  NOT NULL,
    actor_role      VARCHAR(500),
    action          VARCHAR(60)   NOT NULL,
    entity_type     VARCHAR(40)   NOT NULL,
    entity_id       VARCHAR(100)  NOT NULL,
    correlation_id  VARCHAR(64),
    detail_json     VARCHAR(4000),
    occurred_at     TIMESTAMP     NOT NULL,
    CONSTRAINT pk_audit_event PRIMARY KEY (id)
);
CREATE INDEX idx_audit_entity ON audit_event (entity_type, entity_id, occurred_at);
CREATE INDEX idx_audit_tenant_time ON audit_event (tenant_id, occurred_at);

CREATE TABLE idempotency_record (
    id               VARCHAR(36)   NOT NULL,
    tenant_id        VARCHAR(36)   NOT NULL,
    idem_key         VARCHAR(128)  NOT NULL,
    request_hash     VARCHAR(64)   NOT NULL,
    status           VARCHAR(20)   NOT NULL,
    response_status  INTEGER,
    response_body    VARCHAR(4000),
    message_id       VARCHAR(36),
    created_at       TIMESTAMP     NOT NULL,
    expires_at       TIMESTAMP     NOT NULL,
    CONSTRAINT pk_idempotency_record PRIMARY KEY (id),
    CONSTRAINT uq_idempotency UNIQUE (tenant_id, idem_key)
);
CREATE INDEX idx_idempotency_expires ON idempotency_record (expires_at);

CREATE TABLE processed_message (
    id            VARCHAR(36)  NOT NULL,
    dispatch_id   VARCHAR(100) NOT NULL,
    message_id    VARCHAR(36)  NOT NULL,
    processed_at  TIMESTAMP    NOT NULL,
    CONSTRAINT pk_processed_message PRIMARY KEY (id),
    CONSTRAINT uq_processed_dispatch UNIQUE (dispatch_id)
);

CREATE TABLE outbox_event (
    id            VARCHAR(36)   NOT NULL,
    aggregate_id  VARCHAR(36)   NOT NULL,
    topic         VARCHAR(100)  NOT NULL,
    payload       VARCHAR(4000) NOT NULL,
    status        VARCHAR(10)   NOT NULL,
    available_at  TIMESTAMP     NOT NULL,
    created_at    TIMESTAMP     NOT NULL,
    sent_at       TIMESTAMP,
    CONSTRAINT pk_outbox_event PRIMARY KEY (id)
);
CREATE INDEX idx_outbox_status_available ON outbox_event (status, available_at);

CREATE TABLE replay_guard (
    event_id  VARCHAR(200) NOT NULL,
    seen_at   TIMESTAMP    NOT NULL,
    CONSTRAINT pk_replay_guard PRIMARY KEY (event_id)
);
