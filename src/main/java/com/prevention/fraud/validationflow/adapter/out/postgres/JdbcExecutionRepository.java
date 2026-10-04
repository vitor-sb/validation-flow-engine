package com.prevention.fraud.validationflow.adapter.out.postgres;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.prevention.fraud.validationflow.application.ExecutionRepository;
import com.prevention.fraud.validationflow.domain.ExecutionStatus;
import com.prevention.fraud.validationflow.domain.FlowExecution;
import com.prevention.fraud.validationflow.domain.NodeExecution;

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
		return jdbc.query("SELECT * FROM flow_execution WHERE tenant_id = ? AND id = ?", (rs, n) -> read(rs), tenantId, id)
				.stream().findFirst();
	}

	private FlowExecution read(java.sql.ResultSet rs) throws java.sql.SQLException {
		Timestamp done = rs.getTimestamp("completed_at");
		return new FlowExecution(rs.getObject("id", UUID.class), rs.getString("tenant_id"),
				rs.getObject("flow_definition_id", UUID.class), rs.getString("flow_key"), rs.getInt("flow_version"),
				map(rs.getString("flow_snapshot")), rs.getString("correlation_id"),
				ExecutionStatus.valueOf(rs.getString("status")), map(rs.getString("input_data")),
				map(rs.getString("context_data")), map(rs.getString("result")), map(rs.getString("error_info")),
				rs.getLong("lock_version"), rs.getTimestamp("started_at").toInstant(),
				done == null ? null : done.toInstant());
	}

	@Override
	public void recordNode(String tenantId, UUID executionId, String nodeId, String nodeType, int attempt,
			String status, Map<String, Object> output, Map<String, Object> error, Map<String, Object> input,
			java.time.Instant startedAt) {
		jdbc.update("""
				INSERT INTO node_execution (id, tenant_id, execution_id, node_id, node_type, attempt, status,
				    input_snapshot, output_data, error_info, started_at, completed_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, now())""", UUID.randomUUID(), tenantId,
				executionId, nodeId, nodeType, attempt, status, json.writeValueAsString(input),
				json.writeValueAsString(output), error == null ? null : json.writeValueAsString(error),
				Timestamp.from(startedAt));
	}

	@Override
	public List<NodeExecution> findNodes(String tenantId, UUID executionId) {
		return jdbc.query("""
				SELECT * FROM node_execution WHERE tenant_id = ? AND execution_id = ?
				ORDER BY started_at, completed_at, attempt""", (rs, n) -> {
			Timestamp done = rs.getTimestamp("completed_at");
			return new NodeExecution(rs.getString("node_id"), rs.getString("node_type"), rs.getInt("attempt"),
					rs.getString("status"), map(rs.getString("input_snapshot")), map(rs.getString("output_data")),
					map(rs.getString("error_info")), rs.getTimestamp("started_at").toInstant(),
					done == null ? null : done.toInstant());
		}, tenantId, executionId);
	}

	@Override
	public List<FlowExecution> list(String tenantId, int limit, long offset) {
		return jdbc.query("SELECT * FROM flow_execution WHERE tenant_id = ? ORDER BY started_at DESC, id LIMIT ? OFFSET ?",
				(rs, n) -> read(rs), tenantId, limit, offset);
	}

	@Override
	public long count(String tenantId) {
		return jdbc.queryForObject("SELECT count(*) FROM flow_execution WHERE tenant_id = ?", Long.class, tenantId);
	}

	private Map<String, Object> map(String j) {
		return j == null ? null : json.readValue(j, MAP);
	}

	@Override
	public void audit(String tenantId, UUID executionId, String eventType, String nodeId, Map<String, Object> details) {
		jdbc.update("INSERT INTO execution_audit_log (id, tenant_id, execution_id, event_type, node_id, details) "
				+ "VALUES (?, ?, ?, ?, ?, ?::jsonb)", UUID.randomUUID(), tenantId, executionId, eventType, nodeId,
				json.writeValueAsString(details));
	}

	@Override
	public boolean claimIdempotency(String tenantId, String key, String requestHash) {
		return jdbc.update("INSERT INTO idempotency_key (tenant_id, idempotency_key, request_hash) VALUES (?, ?, ?) "
				+ "ON CONFLICT DO NOTHING", tenantId, key, requestHash) == 1;
	}

	@Override
	public Optional<IdempotencyClaim> findIdempotency(String tenantId, String key) {
		return jdbc.query("SELECT request_hash, execution_id FROM idempotency_key WHERE tenant_id = ? AND idempotency_key = ?",
				(rs, n) -> new IdempotencyClaim(rs.getString(1), rs.getObject(2, UUID.class)), tenantId, key).stream()
				.findFirst();
	}

	@Override
	public void completeIdempotency(String tenantId, String key, UUID executionId) {
		jdbc.update("UPDATE idempotency_key SET execution_id = ? WHERE tenant_id = ? AND idempotency_key = ?",
				executionId, tenantId, key);
	}

	@Override
	public void releaseIdempotency(String tenantId, String key) {
		jdbc.update("DELETE FROM idempotency_key WHERE tenant_id = ? AND idempotency_key = ? AND execution_id IS NULL",
				tenantId, key);
	}

}
