package com.prevention.fraud.validationflow.adapter.out.postgres.entity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import com.prevention.fraud.validationflow.domain.flow.FlowStatus;
import com.prevention.fraud.validationflow.domain.flow.InputField;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/** Row of flow_definition; the id is assigned by the application, so {@link Persistable} avoids a SELECT before INSERT. */
@Entity
@Table(name = "flow_definition")
public class FlowDefinitionEntity implements Persistable<UUID> {

	@Id
	public UUID id;

	@Column(name = "tenant_id")
	public String tenantId;

	@Column(name = "flow_key")
	public String flowKey;

	public int version;

	@Enumerated(EnumType.STRING)
	public FlowStatus status;

	@Column(name = "user_type")
	public String userType;

	public String context;

	@Column(name = "display_name")
	public String displayName;

	public String description;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "graph_definition")
	public Map<String, Object> graphDefinition;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "input_contract")
	public List<InputField> inputContract;

	@JdbcTypeCode(SqlTypes.JSON)
	public Map<String, Object> metadata;

	@Column(name = "created_by")
	public String createdBy;

	@Column(name = "created_at")
	public Instant createdAt;

	@Column(name = "updated_at")
	public Instant updatedAt;

	@Transient
	private boolean isNew = true;

	@Override
	public UUID getId() {
		return id;
	}

	@Override
	public boolean isNew() {
		return isNew;
	}

	@PostPersist
	@PostLoad
	void markNotNew() {
		isNew = false;
	}

}
