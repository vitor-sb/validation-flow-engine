CREATE TABLE provider_cache_entry (
    id             UUID PRIMARY KEY,
    tenant_id      VARCHAR(64)  NOT NULL,
    provider_key   VARCHAR(100) NOT NULL,
    operation      VARCHAR(100) NOT NULL,
    request_key    VARCHAR(64)  NOT NULL,
    response       JSONB        NOT NULL,
    data_category  VARCHAR(32)  NOT NULL,
    fetched_at     TIMESTAMPTZ  NOT NULL,
    valid_until    TIMESTAMPTZ  NOT NULL,
    policy_applied VARCHAR(200)
);

CREATE UNIQUE INDEX ux_provider_cache_entry_key ON provider_cache_entry (tenant_id, provider_key, operation, request_key);
CREATE INDEX ix_provider_cache_entry_valid_until ON provider_cache_entry (valid_until);

CREATE TABLE provider_cache_policy (
    tenant_id                 VARCHAR(64)  NOT NULL,
    provider_key              VARCHAR(100) NOT NULL,
    operation                 VARCHAR(100) NOT NULL,
    data_category             VARCHAR(32)  NOT NULL,
    ttl_seconds               INTEGER,
    fallback_on_error_allowed BOOLEAN      NOT NULL DEFAULT false,
    justification             VARCHAR(500),
    owner                     VARCHAR(200),
    review_date               DATE,
    PRIMARY KEY (tenant_id, provider_key, operation)
);
