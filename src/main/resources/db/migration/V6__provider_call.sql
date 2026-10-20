CREATE TABLE provider_call (
    id                  UUID PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    execution_id        UUID         NOT NULL,
    node_id             VARCHAR(128) NOT NULL,
    provider_key        VARCHAR(100) NOT NULL,
    external_ref        VARCHAR(500),
    mode                VARCHAR(16)  NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    attempts            INTEGER      NOT NULL DEFAULT 0,
    next_poll_at        TIMESTAMPTZ,
    deadline            TIMESTAMPTZ,
    callback_token_hash VARCHAR(64),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_provider_call_poll ON provider_call (next_poll_at) WHERE status = 'PENDING' AND mode = 'POLL';
CREATE INDEX ix_provider_call_execution ON provider_call (tenant_id, execution_id);
