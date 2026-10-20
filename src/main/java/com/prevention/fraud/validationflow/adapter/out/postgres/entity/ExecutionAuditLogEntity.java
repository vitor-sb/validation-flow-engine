package com.prevention.fraud.validationflow.adapter.out.postgres.entity;

import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** created_at is left to the column default. */
@Entity
@Table(name = "execution_audit_log")
public class ExecutionAuditLogEntity extends AssignedIdEntity {

	@Column(name = "tenant_id")
	public String tenantId;

	@Column(name = "execution_id")
	public UUID executionId;

	@Column(name = "event_type")
	public String eventType;

	@Column(name = "node_id")
	public String nodeId;

	@JdbcTypeCode(SqlTypes.JSON)
	public Map<String, Object> details;

}
