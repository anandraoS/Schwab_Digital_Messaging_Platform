-- Portable DDL: runs unchanged on PostgreSQL 16 and H2 2.x (MODE=PostgreSQL).
-- No partial indexes, no JSONB, no ON CONFLICT, no vendor-specific types.

CREATE TABLE tenant (
    id          VARCHAR(36)  NOT NULL,
    name        VARCHAR(200) NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,
    CONSTRAINT pk_tenant PRIMARY KEY (id)
);

CREATE TABLE recipient (
    id            VARCHAR(36)  NOT NULL,
    tenant_id     VARCHAR(36)  NOT NULL,
    external_ref  VARCHAR(100) NOT NULL,
    display_name  VARCHAR(200),
    email         VARCHAR(320),
    phone_e164    VARCHAR(20),
    push_token    VARCHAR(255),
    locale        VARCHAR(20)  NOT NULL,
    time_zone     VARCHAR(50)  NOT NULL,
    deleted       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP    NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_recipient PRIMARY KEY (id),
    CONSTRAINT fk_recipient_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT uq_recipient_external UNIQUE (tenant_id, external_ref)
);
CREATE INDEX idx_recipient_tenant_name ON recipient (tenant_id, display_name);

CREATE TABLE recipient_preference (
    recipient_id     VARCHAR(36)  NOT NULL,
    marketing_opt_in BOOLEAN      NOT NULL DEFAULT FALSE,
    email_enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    sms_enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    push_enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    quiet_start      TIME,
    quiet_end        TIME,
    fallback_order   VARCHAR(100),
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL,
    version          BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_recipient_preference PRIMARY KEY (recipient_id),
    CONSTRAINT fk_pref_recipient FOREIGN KEY (recipient_id) REFERENCES recipient (id)
);

CREATE TABLE suppression (
    id            VARCHAR(36)  NOT NULL,
    tenant_id     VARCHAR(36)  NOT NULL,
    channel       VARCHAR(10)  NOT NULL,
    address_hash  VARCHAR(64)  NOT NULL,
    reason        VARCHAR(100) NOT NULL,
    created_at    TIMESTAMP    NOT NULL,
    CONSTRAINT pk_suppression PRIMARY KEY (id),
    CONSTRAINT uq_suppression UNIQUE (tenant_id, channel, address_hash)
);
