CREATE TABLE template (
    id          VARCHAR(36)  NOT NULL,
    tenant_id   VARCHAR(36)  NOT NULL,
    code        VARCHAR(100) NOT NULL,
    name        VARCHAR(200) NOT NULL,
    channel     VARCHAR(10)  NOT NULL,
    category    VARCHAR(30)  NOT NULL,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,
    CONSTRAINT pk_template PRIMARY KEY (id),
    CONSTRAINT fk_template_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT uq_template_code UNIQUE (tenant_id, code, channel)
);

CREATE TABLE template_version (
    id                 VARCHAR(36)    NOT NULL,
    template_id        VARCHAR(36)    NOT NULL,
    version_no         INTEGER        NOT NULL,
    locale             VARCHAR(20)    NOT NULL,
    status             VARCHAR(30)    NOT NULL,
    subject            VARCHAR(500),
    body               VARCHAR(10000) NOT NULL,
    variables_json     VARCHAR(2000)  NOT NULL,
    created_by         VARCHAR(200)   NOT NULL,
    approved_by        VARCHAR(200),
    published_at       TIMESTAMP,
    rejection_comment  VARCHAR(1000),
    created_at         TIMESTAMP      NOT NULL,
    updated_at         TIMESTAMP      NOT NULL,
    version            BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT pk_template_version PRIMARY KEY (id),
    CONSTRAINT fk_tv_template FOREIGN KEY (template_id) REFERENCES template (id),
    CONSTRAINT uq_template_version UNIQUE (template_id, version_no, locale)
);
CREATE INDEX idx_tv_template_status ON template_version (template_id, status);
