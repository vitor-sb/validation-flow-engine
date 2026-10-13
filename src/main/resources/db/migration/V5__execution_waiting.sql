ALTER TABLE flow_execution
    ADD COLUMN waiting_node_id VARCHAR(128),
    ADD COLUMN wait_deadline   TIMESTAMPTZ,
    ADD COLUMN wait_reason     VARCHAR(500);

CREATE INDEX ix_flow_execution_wait_deadline ON flow_execution (wait_deadline) WHERE status = 'WAITING';
