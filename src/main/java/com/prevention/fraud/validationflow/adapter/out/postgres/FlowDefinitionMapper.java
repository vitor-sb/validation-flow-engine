package com.prevention.fraud.validationflow.adapter.out.postgres;

import java.util.List;

import com.prevention.fraud.validationflow.domain.flow.FlowDefinition;

final class FlowDefinitionMapper {

	private FlowDefinitionMapper() {
	}

	static FlowDefinitionEntity toEntity(FlowDefinition f) {
		var e = new FlowDefinitionEntity();
		e.id = f.id();
		e.tenantId = f.tenantId();
		e.flowKey = f.flowKey();
		e.version = f.version();
		e.status = f.status();
		e.userType = f.userType();
		e.context = f.context();
		e.displayName = f.displayName();
		e.description = f.description();
		e.graphDefinition = f.graphDefinition();
		e.inputContract = f.inputContract();
		e.metadata = f.metadata();
		e.createdBy = f.createdBy();
		e.createdAt = f.createdAt();
		e.updatedAt = f.createdAt();
		return e;
	}

	static FlowDefinition toDomain(FlowDefinitionEntity e) {
		return new FlowDefinition(e.id, e.tenantId, e.flowKey, e.version, e.status, e.userType, e.context,
				e.displayName, e.description, e.graphDefinition,
				e.inputContract == null ? List.of() : e.inputContract, e.metadata, e.createdBy, e.createdAt);
	}

}
