package com.prevention.fraud.validationflow.adapter.out.postgres;

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
class ExecutionAuditLogEntity extends AssignedIdEntity {

	@Column(name = "tenant_id")
	String tenantId;

	@Column(name = "execution_id")
	UUID executionId;

	@Column(name = "event_type")
	String eventType;

	@Column(name = "node_id")
	String nodeId;

	@JdbcTypeCode(SqlTypes.JSON)
	Map<String, Object> details;

}
