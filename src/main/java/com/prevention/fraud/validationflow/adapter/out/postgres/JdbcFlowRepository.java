package com.prevention.fraud.validationflow.adapter.out.postgres;

import java.sql.Timestamp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.prevention.fraud.validationflow.application.FlowRepository;
import com.prevention.fraud.validationflow.domain.FlowDefinition;

import tools.jackson.databind.json.JsonMapper;

@Repository
class JdbcFlowRepository implements FlowRepository {

	private final JdbcTemplate jdbc;

	private final JsonMapper json;

	JdbcFlowRepository(JdbcTemplate jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
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

}
