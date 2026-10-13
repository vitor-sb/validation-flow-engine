CREATE TABLE provider_config_audit (
    id           UUID PRIMARY KEY,
    tenant_id    VARCHAR(100) NOT NULL,
    provider_key VARCHAR(100) NOT NULL,
    actor        VARCHAR(200),
    event_type   VARCHAR(64)  NOT NULL,
    details      JSONB,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_provider_config_audit ON provider_config_audit (tenant_id, provider_key, created_at);
