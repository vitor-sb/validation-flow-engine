package com.prevention.fraud.validationflow.application;

import java.util.Optional;
import java.util.UUID;

import com.prevention.fraud.validationflow.domain.FlowDefinition;

/** Port; every operation is scoped by the tenant carried in the definition or argument. */
public interface FlowRepository {

	/** Next version for the key within the tenant (1 when none exists). */
	int nextVersion(String tenantId, String flowKey);

	FlowDefinition save(FlowDefinition flow);

	Optional<FlowDefinition> findById(String tenantId, UUID id);

	/** Rewrites a DRAFT in place; false when the flow is not a DRAFT of that tenant. */
	boolean updateDraft(FlowDefinition flow);

	/**
	 * In one transaction archives the tenant's ACTIVE version of the same (userType, context) and activates
	 * this DRAFT. False when the flow is no longer a DRAFT; a concurrent activation of the same selector
	 * surfaces as DuplicateKeyException.
	 */
	boolean activate(String tenantId, UUID id);

	/** Archives a non-archived flow; false when not found or already archived. */
	boolean archive(String tenantId, UUID id);

}
