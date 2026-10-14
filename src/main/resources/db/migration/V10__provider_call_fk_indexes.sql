-- orphans cannot exist in a fresh schema, but clean any left by earlier versions before adding the FK
DELETE FROM provider_call c WHERE NOT EXISTS (SELECT 1 FROM flow_execution e WHERE e.id = c.execution_id);

ALTER TABLE provider_call
    ADD CONSTRAINT fk_provider_call_execution FOREIGN KEY (execution_id) REFERENCES flow_execution (id);

-- no query filters on (tenant_id, execution_id); the FK needs execution_id as the leading column
DROP INDEX ix_provider_call_execution;
CREATE INDEX ix_provider_call_execution ON provider_call (execution_id);

-- sweepDead and the global pending/dead gauges scan by status across tenants; COMPLETED is the bulk of the table
CREATE INDEX ix_provider_call_open ON provider_call (status) WHERE status <> 'COMPLETED';
