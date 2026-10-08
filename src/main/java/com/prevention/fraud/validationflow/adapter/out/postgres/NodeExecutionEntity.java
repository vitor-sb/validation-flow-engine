package com.prevention.fraud.validationflow.adapter.out.postgres;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "node_execution")
class NodeExecutionEntity extends AssignedIdEntity {

	@Column(name = "tenant_id")
	String tenantId;

	@Column(name = "execution_id")
	UUID executionId;

	@Column(name = "node_id")
	String nodeId;

	@Column(name = "node_type")
	String nodeType;

	int attempt;

	String status;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "input_snapshot")
	Map<String, Object> inputSnapshot;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "output_data")
	Map<String, Object> outputData;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "error_info")
	Map<String, Object> errorInfo;

	@Column(name = "started_at")
	Instant startedAt;

	@Column(name = "completed_at")
	Instant completedAt;

}
