package com.prevention.fraud.validationflow.adapter.out.postgres.entity;

import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** created_at is left to the column default. */
@Entity
@Table(name = "provider_config_audit")
public class ProviderConfigAuditEntity extends AssignedIdEntity {

	@Column(name = "tenant_id")
	public String tenantId;

	@Column(name = "provider_key")
	public String providerKey;

	public String actor;

	@Column(name = "event_type")
	public String eventType;

	@JdbcTypeCode(SqlTypes.JSON)
	public Map<String, Object> details;

}
