package com.prevention.fraud.validationflow.application;

import java.util.Map;
import java.util.UUID;

import com.prevention.fraud.validationflow.domain.FlowExecution;
import com.prevention.fraud.validationflow.domain.NodeExecution;

/** Port; every operation is scoped by tenant. */
public interface ExecutionRepository {

	/** Inserts a new execution (lockVersion as given). */
	void insert(FlowExecution execution);

	/** Persists the state change if the stored lockVersion still equals the given one, bumping it; false otherwise. */
	boolean update(FlowExecution execution);

	java.util.Optional<FlowExecution> findById(String tenantId, UUID id);

	void recordNode(String tenantId, UUID executionId, String nodeId, String nodeType, int attempt,
			String status, Map<String, Object> output, Map<String, Object> error, Map<String, Object> input,
			java.time.Instant startedAt);

	java.util.List<NodeExecution> findNodes(String tenantId, UUID executionId);

	/** Newest first. */
	java.util.List<FlowExecution> list(String tenantId, int limit, long offset);

	long count(String tenantId);

	void audit(String tenantId, UUID executionId, String eventType, String nodeId, Map<String, Object> details);

	/** Atomically claims (tenant, key); false if already claimed. */
	boolean claimIdempotency(String tenantId, String key, String requestHash);

	/** The claim as (requestHash, executionId or null while the winner is still running). */
	java.util.Optional<IdempotencyClaim> findIdempotency(String tenantId, String key);

	void completeIdempotency(String tenantId, String key, UUID executionId);

	/** Drops a claim whose execution never started (e.g. rejected input), so the key can be reused. */
	void releaseIdempotency(String tenantId, String key);

	record IdempotencyClaim(String requestHash, UUID executionId) {
	}

}
