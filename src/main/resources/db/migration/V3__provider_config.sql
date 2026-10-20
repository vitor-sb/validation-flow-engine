CREATE TABLE provider_config (
    tenant_id          VARCHAR(100) NOT NULL,
    provider_key       VARCHAR(100) NOT NULL,
    enabled            BOOLEAN      NOT NULL DEFAULT false,
    base_url           VARCHAR(500),
    connect_timeout_ms INTEGER,
    read_timeout_ms    INTEGER,
    credentials_ref    VARCHAR(200),
    updated_by         VARCHAR(200),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, provider_key)
);
