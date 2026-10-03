package com.prevention.fraud.validationflow.adapter.out.postgres;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Repository;

import com.prevention.fraud.validationflow.application.FlowRepository;
import com.prevention.fraud.validationflow.domain.FlowDefinition;
import com.prevention.fraud.validationflow.domain.FlowStatus;
import com.prevention.fraud.validationflow.domain.InputField;

import tools.jackson.core.type.TypeReference;

import tools.jackson.databind.json.JsonMapper;

@Repository
class JdbcFlowRepository implements FlowRepository {

	private final JdbcTemplate jdbc;

	private final JsonMapper json;

	private final TransactionTemplate tx;

	JdbcFlowRepository(JdbcTemplate jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
		this.tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
	}

	@Override
	public int nextVersion(String tenantId, String flowKey) {
		return jdbc.queryForObject(
				"SELECT COALESCE(MAX(version), 0) + 1 FROM flow_definition WHERE tenant_id = ? AND flow_key = ?",
				Integer.class, tenantId, flowKey);
	}

	@Override
	public FlowDefinition save(FlowDefinition f) {
		jdbc.update("""
				INSERT INTO flow_definition (id, tenant_id, flow_key, version, status, user_type, context,
				    display_name, description, graph_definition, input_contract, metadata, created_by,
				    created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?)""",
				f.id(), f.tenantId(), f.flowKey(), f.version(), f.status().name(), f.userType(), f.context(),
				f.displayName(), f.description(), json.writeValueAsString(f.graphDefinition()),
				json.writeValueAsString(f.inputContract()), json.writeValueAsString(f.metadata()), f.createdBy(),
				Timestamp.from(f.createdAt()), Timestamp.from(f.createdAt()));
		return f;
	}

	@Override
	public Optional<FlowDefinition> findById(String tenantId, UUID id) {
		return jdbc.query("SELECT * FROM flow_definition WHERE tenant_id = ? AND id = ?", (rs, n) -> new FlowDefinition(
				rs.getObject("id", UUID.class), rs.getString("tenant_id"), rs.getString("flow_key"),
				rs.getInt("version"), FlowStatus.valueOf(rs.getString("status")), rs.getString("user_type"),
				rs.getString("context"), rs.getString("display_name"), rs.getString("description"),
				json.readValue(rs.getString("graph_definition"), new TypeReference<Map<String, Object>>() { }),
				orEmpty(json.readValue(rs.getString("input_contract"), new TypeReference<List<InputField>>() { })),
				json.readValue(rs.getString("metadata"), new TypeReference<Map<String, Object>>() { }),
				rs.getString("created_by"), rs.getTimestamp("created_at").toInstant()), tenantId, id)
				.stream().findFirst();
	}

	private static List<InputField> orEmpty(List<InputField> l) {
		return l == null ? List.of() : l;
	}

	@Override
	public boolean updateDraft(FlowDefinition f) {
		return jdbc.update("""
				UPDATE flow_definition SET user_type = ?, context = ?, display_name = ?, description = ?,
				    graph_definition = ?::jsonb, input_contract = ?::jsonb, metadata = ?::jsonb, updated_at = ?
				WHERE tenant_id = ? AND id = ? AND status = 'DRAFT'""", f.userType(), f.context(), f.displayName(),
				f.description(), json.writeValueAsString(f.graphDefinition()),
				json.writeValueAsString(f.inputContract()), json.writeValueAsString(f.metadata()),
				Timestamp.from(Instant.now()), f.tenantId(), f.id()) == 1;
	}

	@Override
	public boolean activate(String tenantId, UUID id) {
		return Boolean.TRUE.equals(tx.execute(status -> {
			// archive first: the partial unique index only admits one ACTIVE per selector
			jdbc.update("""
					UPDATE flow_definition SET status = 'ARCHIVED', updated_at = now()
					WHERE tenant_id = ? AND status = 'ACTIVE' AND (user_type, context) =
					    (SELECT user_type, context FROM flow_definition WHERE tenant_id = ? AND id = ?)""",
					tenantId, tenantId, id);
			if (jdbc.update("UPDATE flow_definition SET status = 'ACTIVE', updated_at = now() "
					+ "WHERE tenant_id = ? AND id = ? AND status = 'DRAFT'", tenantId, id) == 1) {
				return true;
			}
			status.setRollbackOnly(); // not a DRAFT: undo the archive above
			return false;
		}));
	}

	@Override
	public boolean archive(String tenantId, UUID id) {
		return jdbc.update("UPDATE flow_definition SET status = 'ARCHIVED', updated_at = now() "
				+ "WHERE tenant_id = ? AND id = ? AND status <> 'ARCHIVED'", tenantId, id) == 1;
	}

}
