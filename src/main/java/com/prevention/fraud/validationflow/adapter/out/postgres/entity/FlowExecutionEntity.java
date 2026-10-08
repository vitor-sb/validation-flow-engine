package com.prevention.fraud.validationflow.adapter.out.postgres.entity;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.prevention.fraud.validationflow.domain.execution.ExecutionStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "flow_execution")
public class FlowExecutionEntity extends AssignedIdEntity {

	@Column(name = "tenant_id")
	public String tenantId;

	@Column(name = "flow_definition_id")
	public UUID flowDefinitionId;

	@Column(name = "flow_key")
	public String flowKey;

	@Column(name = "flow_version")
	public int flowVersion;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "flow_snapshot")
	public Map<String, Object> flowSnapshot;

	@Column(name = "correlation_id")
	public String correlationId;

	@Enumerated(EnumType.STRING)
	public ExecutionStatus status;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "input_data")
	public Map<String, Object> inputData;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "context_data")
	public Map<String, Object> contextData;

	@JdbcTypeCode(SqlTypes.JSON)
	public Map<String, Object> result;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "error_info")
	public Map<String, Object> errorInfo;

	@Column(name = "lock_version")
	public long lockVersion;

	@Column(name = "started_at")
	public Instant startedAt;

	@Column(name = "completed_at")
	public Instant completedAt;

	@Column(name = "parent_execution_id")
	public UUID parentExecutionId;

	@Column(name = "parent_node_id")
	public String parentNodeId;

}
