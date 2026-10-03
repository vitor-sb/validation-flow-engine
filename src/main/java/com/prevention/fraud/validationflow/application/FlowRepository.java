package com.prevention.fraud.validationflow.application;

import com.prevention.fraud.validationflow.domain.FlowDefinition;

/** Port; every operation is scoped by the tenant carried in the definition or argument. */
public interface FlowRepository {

	/** Next version for the key within the tenant (1 when none exists). */
	int nextVersion(String tenantId, String flowKey);

	FlowDefinition save(FlowDefinition flow);

}
