ALTER TABLE provider_call ADD COLUMN reason VARCHAR(100);

CREATE INDEX ix_provider_call_status ON provider_call (tenant_id, status, updated_at DESC);
