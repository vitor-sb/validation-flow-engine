package com.prevention.fraud.validationflow.application;

import java.util.Map;
import java.util.UUID;

import com.prevention.fraud.validationflow.domain.FlowExecution;

/** Port; every operation is scoped by tenant. */
public interface ExecutionRepository {

	/** Inserts a new execution (lockVersion as given). */
	void insert(FlowExecution execution);

	/** Persists the state change if the stored lockVersion still equals the given one, bumping it; false otherwise. */
	boolean update(FlowExecution execution);

	java.util.Optional<FlowExecution> findById(String tenantId, UUID id);

	void recordNode(String tenantId, UUID executionId, String nodeId, String nodeType, int attempt,
			String status, Map<String, Object> output, Map<String, Object> error);

	void audit(String tenantId, UUID executionId, String eventType, String nodeId, Map<String, Object> details);

}
