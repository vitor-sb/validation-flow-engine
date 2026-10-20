CREATE TABLE flow_definition (
    id               UUID PRIMARY KEY,
    tenant_id        VARCHAR(64)  NOT NULL,
    flow_key         VARCHAR(128) NOT NULL,
    version          INT          NOT NULL,
    status           VARCHAR(16)  NOT NULL,
    user_type        VARCHAR(64)  NOT NULL,
    context          VARCHAR(64)  NOT NULL,
    display_name     VARCHAR(255) NOT NULL,
    description      TEXT,
    graph_definition JSONB        NOT NULL,
    input_contract   JSONB,
    metadata         JSONB,
    created_by       VARCHAR(128) NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_flow_definition_key_version UNIQUE (tenant_id, flow_key, version)
);

-- at most one ACTIVE version per selector
CREATE UNIQUE INDEX uq_flow_definition_active_selector
    ON flow_definition (tenant_id, user_type, context) WHERE status = 'ACTIVE';

CREATE TABLE flow_execution (
    id                  UUID PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    flow_definition_id  UUID         NOT NULL REFERENCES flow_definition (id),
    flow_key            VARCHAR(128) NOT NULL,
    flow_version        INT          NOT NULL,
    flow_snapshot       JSONB        NOT NULL,
    parent_execution_id UUID REFERENCES flow_execution (id),
    parent_node_id      VARCHAR(128),
    correlation_id      VARCHAR(128),
    status              VARCHAR(16)  NOT NULL,
    input_data          JSONB        NOT NULL,
    context_data        JSONB,
    result              JSONB,
    error_info          JSONB,
    lock_version        BIGINT       NOT NULL DEFAULT 0,
    started_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ
);
CREATE INDEX ix_flow_execution_tenant_started ON flow_execution (tenant_id, started_at DESC);
CREATE INDEX ix_flow_execution_parent ON flow_execution (parent_execution_id);

CREATE TABLE node_execution (
    id             UUID PRIMARY KEY,
    tenant_id      VARCHAR(64)  NOT NULL,
    execution_id   UUID         NOT NULL REFERENCES flow_execution (id),
    node_id        VARCHAR(128) NOT NULL,
    node_type      VARCHAR(32)  NOT NULL,
    attempt        INT          NOT NULL DEFAULT 1,
    status         VARCHAR(16)  NOT NULL,
    input_snapshot JSONB,
    output_data    JSONB,
    error_info     JSONB,
    started_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at   TIMESTAMPTZ,
    CONSTRAINT uq_node_execution_attempt UNIQUE (execution_id, node_id, attempt)
);

CREATE TABLE execution_audit_log (
    id           UUID PRIMARY KEY,
    tenant_id    VARCHAR(64)  NOT NULL,
    execution_id UUID         NOT NULL REFERENCES flow_execution (id),
    event_type   VARCHAR(64)  NOT NULL,
    node_id      VARCHAR(128),
    details      JSONB,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_log_execution ON execution_audit_log (execution_id, created_at);

CREATE TABLE idempotency_key (
    tenant_id       VARCHAR(64)  NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash    VARCHAR(128) NOT NULL,
    execution_id    UUID REFERENCES flow_execution (id),
    response        JSONB,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, idempotency_key)
);
