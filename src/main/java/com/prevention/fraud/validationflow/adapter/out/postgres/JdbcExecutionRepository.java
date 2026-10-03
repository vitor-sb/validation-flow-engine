package com.prevention.fraud.validationflow.adapter.out.postgres;

import java.sql.Timestamp;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.prevention.fraud.validationflow.application.ExecutionRepository;
import com.prevention.fraud.validationflow.domain.ExecutionStatus;
import com.prevention.fraud.validationflow.domain.FlowExecution;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
class JdbcExecutionRepository implements ExecutionRepository {

	private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

	private final JdbcTemplate jdbc;

	private final JsonMapper json;

	JdbcExecutionRepository(JdbcTemplate jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	@Override
	public void insert(FlowExecution e) {
		jdbc.update("""
				INSERT INTO flow_execution (id, tenant_id, flow_definition_id, flow_key, flow_version, flow_snapshot,
				    correlation_id, status, input_data, context_data, lock_version, started_at)
				VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb, ?::jsonb, ?, ?)""", e.id(), e.tenantId(),
				e.flowDefinitionId(), e.flowKey(), e.flowVersion(), json.writeValueAsString(e.snapshot()),
				e.correlationId(), e.status().name(), json.writeValueAsString(e.inputData()),
				json.writeValueAsString(e.contextData()), e.lockVersion(), Timestamp.from(e.startedAt()));
	}

	@Override
	public boolean update(FlowExecution e) {
		return jdbc.update("""
				UPDATE flow_execution SET status = ?, context_data = ?::jsonb, result = ?::jsonb,
				    error_info = ?::jsonb, completed_at = ?, lock_version = lock_version + 1
				WHERE tenant_id = ? AND id = ? AND lock_version = ?""", e.status().name(),
				json.writeValueAsString(e.contextData()), e.result() == null ? null : json.writeValueAsString(e.result()),
				e.errorInfo() == null ? null : json.writeValueAsString(e.errorInfo()),
				e.completedAt() == null ? null : Timestamp.from(e.completedAt()), e.tenantId(), e.id(),
				e.lockVersion()) == 1;
	}

	@Override
	public Optional<FlowExecution> findById(String tenantId, UUID id) {
		return jdbc.query("SELECT * FROM flow_execution WHERE tenant_id = ? AND id = ?", (rs, n) -> {
			String result = rs.getString("result");
			String error = rs.getString("error_info");
			Timestamp done = rs.getTimestamp("completed_at");
			return new FlowExecution(id, tenantId, rs.getObject("flow_definition_id", UUID.class),
					rs.getString("flow_key"), rs.getInt("flow_version"),
					json.readValue(rs.getString("flow_snapshot"), MAP), rs.getString("correlation_id"),
					ExecutionStatus.valueOf(rs.getString("status")), json.readValue(rs.getString("input_data"), MAP),
					json.readValue(rs.getString("context_data"), MAP),
					result == null ? null : json.readValue(result, MAP),
					error == null ? null : json.readValue(error, MAP), rs.getLong("lock_version"),
					rs.getTimestamp("started_at").toInstant(), done == null ? null : done.toInstant());
		}, tenantId, id).stream().findFirst();
	}

	@Override
	public void recordNode(String tenantId, UUID executionId, String nodeId, String nodeType, boolean success,
			Map<String, Object> output) {
		jdbc.update("""
				INSERT INTO node_execution (id, tenant_id, execution_id, node_id, node_type, status, output_data,
				    completed_at)
				VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, now())""", UUID.randomUUID(), tenantId, executionId, nodeId,
				nodeType, success ? "COMPLETED" : "FAILED", json.writeValueAsString(output));
	}

	@Override
	public void audit(String tenantId, UUID executionId, String eventType, String nodeId, Map<String, Object> details) {
		jdbc.update("INSERT INTO execution_audit_log (id, tenant_id, execution_id, event_type, node_id, details) "
				+ "VALUES (?, ?, ?, ?, ?, ?::jsonb)", UUID.randomUUID(), tenantId, executionId, eventType, nodeId,
				json.writeValueAsString(details));
	}

}
