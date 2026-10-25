-- Bulk job row ledger: one row per (job, externalRef). The primary key is the in-job duplicate detector and the
-- line number lets a restarted job tell "same row seen again" (replay, safe) from "same ref on another line" (duplicate).
CREATE TABLE bulk_job_row (
    job_id        VARCHAR(36)  NOT NULL,
    external_ref  VARCHAR(100) NOT NULL,
    line_no       BIGINT       NOT NULL,
    created_at    TIMESTAMP    NOT NULL,
    CONSTRAINT pk_bulk_job_row PRIMARY KEY (job_id, external_ref),
    CONSTRAINT fk_bulk_row_job FOREIGN KEY (job_id) REFERENCES bulk_job (id)
);

-- At most one failure record per input line: makes failure recording idempotent across retries and restarts.
CREATE UNIQUE INDEX uq_bulk_failure_job_line ON bulk_job_failure (job_id, line_no);

-- Legal hold honoured by RetentionJob. scope TENANT: nothing of the tenant is purged; scope MESSAGE: the message
-- (entity_id) and its audit trail are kept. released_at NULL means the hold is active.
CREATE TABLE legal_hold (
    id           VARCHAR(36)   NOT NULL,
    tenant_id    VARCHAR(36)   NOT NULL,
    scope        VARCHAR(20)   NOT NULL,
    entity_id    VARCHAR(100),
    reason       VARCHAR(500),
    created_by   VARCHAR(200),
    created_at   TIMESTAMP     NOT NULL,
    released_at  TIMESTAMP,
    CONSTRAINT pk_legal_hold PRIMARY KEY (id)
);
CREATE INDEX idx_legal_hold_active ON legal_hold (tenant_id, scope, released_at);

-- Retention scans by age.
CREATE INDEX idx_audit_occurred ON audit_event (occurred_at);
CREATE INDEX idx_msg_created ON message_request (created_at);
CREATE INDEX idx_event_received ON delivery_event (received_at);
CREATE INDEX idx_processed_at ON processed_message (processed_at);
CREATE INDEX idx_replay_seen ON replay_guard (seen_at);
