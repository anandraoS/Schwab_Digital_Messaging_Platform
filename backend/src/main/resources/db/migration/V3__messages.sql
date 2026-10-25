CREATE TABLE message_request (
    id                   VARCHAR(36)   NOT NULL,
    tenant_id            VARCHAR(36)   NOT NULL,
    client_app           VARCHAR(100),
    client_reference     VARCHAR(200),
    recipient_id         VARCHAR(36)   NOT NULL,
    channel              VARCHAR(10)   NOT NULL,
    category             VARCHAR(30)   NOT NULL,
    priority             VARCHAR(10)   NOT NULL,
    status               VARCHAR(30)   NOT NULL,
    status_reason        VARCHAR(500),
    template_code        VARCHAR(100)  NOT NULL,
    template_version_no  INTEGER       NOT NULL,
    template_locale      VARCHAR(20)   NOT NULL,
    payload_json         VARCHAR(8000) NOT NULL,
    options_json         VARCHAR(2000),
    metadata_json        VARCHAR(2000),
    fallback_channels    VARCHAR(100),
    fallback_index       INTEGER       NOT NULL DEFAULT 0,
    correlation_id       VARCHAR(64)   NOT NULL,
    idempotency_key      VARCHAR(128),
    request_hash         VARCHAR(64),
    scheduled_at         TIMESTAMP,
    expires_at           TIMESTAMP,
    attempt_count        INTEGER       NOT NULL DEFAULT 0,
    max_attempts         INTEGER       NOT NULL DEFAULT 5,
    next_attempt_at      TIMESTAMP,
    channel_used         VARCHAR(10),
    locked_until         TIMESTAMP,
    dlq_reason           VARCHAR(30),
    discarded            BOOLEAN       NOT NULL DEFAULT FALSE,
    bulk_job_id          VARCHAR(36),
    status_changed_at    TIMESTAMP     NOT NULL,
    created_at           TIMESTAMP     NOT NULL,
    updated_at           TIMESTAMP     NOT NULL,
    version              BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_message_request PRIMARY KEY (id),
    CONSTRAINT fk_msg_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_msg_recipient FOREIGN KEY (recipient_id) REFERENCES recipient (id),
    CONSTRAINT uq_msg_idempotency UNIQUE (tenant_id, idempotency_key)
);
CREATE INDEX idx_msg_tenant_status_created ON message_request (tenant_id, status, created_at DESC);
CREATE INDEX idx_msg_recipient_created ON message_request (recipient_id, created_at);
CREATE INDEX idx_msg_status_next_attempt ON message_request (status, next_attempt_at);
CREATE INDEX idx_msg_status_scheduled ON message_request (status, scheduled_at);
CREATE INDEX idx_msg_correlation ON message_request (correlation_id);
CREATE INDEX idx_msg_bulk_job ON message_request (bulk_job_id);
CREATE INDEX idx_msg_client_reference ON message_request (tenant_id, client_reference);

CREATE TABLE message_attempt (
    id                   VARCHAR(36)  NOT NULL,
    message_id           VARCHAR(36)  NOT NULL,
    attempt_no           INTEGER      NOT NULL,
    channel              VARCHAR(10)  NOT NULL,
    provider_id          VARCHAR(100),
    state                VARCHAR(20)  NOT NULL,
    outcome              VARCHAR(30),
    provider_message_id  VARCHAR(200),
    error_code           VARCHAR(100),
    latency_ms           BIGINT,
    started_at           TIMESTAMP    NOT NULL,
    finished_at          TIMESTAMP,
    CONSTRAINT pk_message_attempt PRIMARY KEY (id),
    CONSTRAINT fk_attempt_message FOREIGN KEY (message_id) REFERENCES message_request (id),
    CONSTRAINT uq_attempt UNIQUE (message_id, attempt_no)
);

CREATE TABLE provider_config (
    id                  VARCHAR(36)  NOT NULL,
    provider_id         VARCHAR(100) NOT NULL,
    channel             VARCHAR(10)  NOT NULL,
    enabled             BOOLEAN      NOT NULL,
    priority_order      INTEGER      NOT NULL,
    rate_limit_per_sec  INTEGER      NOT NULL,
    timeout_ms          INTEGER      NOT NULL,
    hmac_key_ref        VARCHAR(100),
    created_at          TIMESTAMP    NOT NULL,
    updated_at          TIMESTAMP    NOT NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_provider_config PRIMARY KEY (id),
    CONSTRAINT uq_provider_config UNIQUE (provider_id)
);

CREATE TABLE delivery_event (
    id                 VARCHAR(36)  NOT NULL,
    message_id         VARCHAR(36)  NOT NULL,
    provider_id        VARCHAR(100) NOT NULL,
    provider_event_id  VARCHAR(200) NOT NULL,
    type               VARCHAR(30)  NOT NULL,
    occurred_at        TIMESTAMP    NOT NULL,
    received_at        TIMESTAMP    NOT NULL,
    applied            BOOLEAN      NOT NULL,
    ignored_reason     VARCHAR(30),
    raw_ref            VARCHAR(100),
    CONSTRAINT pk_delivery_event PRIMARY KEY (id),
    CONSTRAINT fk_event_message FOREIGN KEY (message_id) REFERENCES message_request (id),
    CONSTRAINT uq_delivery_event UNIQUE (provider_id, provider_event_id)
);
CREATE INDEX idx_event_message ON delivery_event (message_id, occurred_at);
CREATE INDEX idx_event_type_received ON delivery_event (type, received_at);
