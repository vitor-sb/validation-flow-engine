package com.prevention.fraud.validationflow.adapter.out.postgres.entity;

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
public class NodeExecutionEntity extends AssignedIdEntity {

	@Column(name = "tenant_id")
	public String tenantId;

	@Column(name = "execution_id")
	public UUID executionId;

	@Column(name = "node_id")
	public String nodeId;

	@Column(name = "node_type")
	public String nodeType;

	public int attempt;

	public String status;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "input_snapshot")
	public Map<String, Object> inputSnapshot;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "output_data")
	public Map<String, Object> outputData;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "error_info")
	public Map<String, Object> errorInfo;

	@Column(name = "started_at")
	public Instant startedAt;

	@Column(name = "completed_at")
	public Instant completedAt;

}
