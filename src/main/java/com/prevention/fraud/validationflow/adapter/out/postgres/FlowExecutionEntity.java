package com.prevention.fraud.validationflow.adapter.out.postgres;

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
class FlowExecutionEntity extends AssignedIdEntity {

	@Column(name = "tenant_id")
	String tenantId;

	@Column(name = "flow_definition_id")
	UUID flowDefinitionId;

	@Column(name = "flow_key")
	String flowKey;

	@Column(name = "flow_version")
	int flowVersion;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "flow_snapshot")
	Map<String, Object> flowSnapshot;

	@Column(name = "correlation_id")
	String correlationId;

	@Enumerated(EnumType.STRING)
	ExecutionStatus status;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "input_data")
	Map<String, Object> inputData;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "context_data")
	Map<String, Object> contextData;

	@JdbcTypeCode(SqlTypes.JSON)
	Map<String, Object> result;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "error_info")
	Map<String, Object> errorInfo;

	@Column(name = "lock_version")
	long lockVersion;

	@Column(name = "started_at")
	Instant startedAt;

	@Column(name = "completed_at")
	Instant completedAt;

	@Column(name = "parent_execution_id")
	UUID parentExecutionId;

	@Column(name = "parent_node_id")
	String parentNodeId;

}
