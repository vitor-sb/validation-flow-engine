package com.prevention.fraud.validationflow.application.execution.ports;

import java.util.Map;
import java.util.UUID;

import com.prevention.fraud.validationflow.domain.execution.FlowExecution;
import com.prevention.fraud.validationflow.domain.execution.NodeExecution;

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

	/** Metrics only: WAITING executions of ALL tenants. */
	long countWaiting();

	void audit(String tenantId, UUID executionId, String eventType, String nodeId, Map<String, Object> details);

	/** Recovery only: RUNNING executions of ALL tenants that started before the cutoff. */
	java.util.List<FlowExecution> findRunningStartedBefore(java.time.Instant cutoff);

	/** WAITING sub-flow executions started by the given parent. */
	java.util.List<FlowExecution> findWaitingChildren(String tenantId, UUID parentId);

	/** Expiry job only: WAITING executions of ALL tenants whose wait_deadline is before the given instant. */
	java.util.List<FlowExecution> findWaitingExpiredBefore(java.time.Instant now);

}
