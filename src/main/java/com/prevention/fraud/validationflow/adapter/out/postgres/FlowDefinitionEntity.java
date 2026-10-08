package com.prevention.fraud.validationflow.adapter.out.postgres;

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
class FlowDefinitionEntity implements Persistable<UUID> {

	@Id
	UUID id;

	@Column(name = "tenant_id")
	String tenantId;

	@Column(name = "flow_key")
	String flowKey;

	int version;

	@Enumerated(EnumType.STRING)
	FlowStatus status;

	@Column(name = "user_type")
	String userType;

	String context;

	@Column(name = "display_name")
	String displayName;

	String description;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "graph_definition")
	Map<String, Object> graphDefinition;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "input_contract")
	List<InputField> inputContract;

	@JdbcTypeCode(SqlTypes.JSON)
	Map<String, Object> metadata;

	@Column(name = "created_by")
	String createdBy;

	@Column(name = "created_at")
	Instant createdAt;

	@Column(name = "updated_at")
	Instant updatedAt;

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
