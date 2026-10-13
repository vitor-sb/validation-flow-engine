package com.prevention.fraud.validationflow.domain.execution;

import com.prevention.fraud.validationflow.domain.flow.FlowDefinition;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One run of a flow over the snapshot of the version resolved at start; state changes only through the domain methods. */
public record FlowExecution(UUID id, String tenantId, UUID flowDefinitionId, String flowKey, int flowVersion,
		Map<String, Object> snapshot, String correlationId, ExecutionStatus status, Map<String, Object> inputData,
		Map<String, Object> contextData, Map<String, Object> result, Map<String, Object> errorInfo,
		long lockVersion, Instant startedAt, Instant completedAt, UUID parentExecutionId, String parentNodeId,
		String waitingNodeId, Instant waitDeadline, String waitReason) {

	/** Non-waiting execution (the wait columns are only set while WAITING). */
	public FlowExecution(UUID id, String tenantId, UUID flowDefinitionId, String flowKey, int flowVersion,
			Map<String, Object> snapshot, String correlationId, ExecutionStatus status, Map<String, Object> inputData,
			Map<String, Object> contextData, Map<String, Object> result, Map<String, Object> errorInfo, long lockVersion,
			Instant startedAt, Instant completedAt, UUID parentExecutionId, String parentNodeId) {
		this(id, tenantId, flowDefinitionId, flowKey, flowVersion, snapshot, correlationId, status, inputData,
				contextData, result, errorInfo, lockVersion, startedAt, completedAt, parentExecutionId, parentNodeId, null,
				null, null);
	}

	public static FlowExecution pending(String tenantId, FlowDefinition flow, String correlationId,
			Map<String, Object> inputData) {
		return pending(tenantId, flow, correlationId, inputData, null, null);
	}

	public static FlowExecution pending(String tenantId, FlowDefinition flow, String correlationId,
			Map<String, Object> inputData, UUID parentExecutionId, String parentNodeId) {
		return new FlowExecution(UUID.randomUUID(), tenantId, flow.id(), flow.flowKey(), flow.version(),
				flow.graphDefinition(), correlationId, ExecutionStatus.PENDING, inputData, Map.of(), null, null, 0,
				Instant.now(), null, parentExecutionId, parentNodeId);
	}

	public FlowExecution start() {
		return to(ExecutionStatus.RUNNING, Set.of(ExecutionStatus.PENDING), contextData, null, null);
	}

	public FlowExecution waitAt(String nodeId, Instant deadline, String reason, Map<String, Object> context) {
		return to(ExecutionStatus.WAITING, Set.of(ExecutionStatus.RUNNING), context, null, null, nodeId, deadline,
				reason);
	}

	public FlowExecution resume() {
		return to(ExecutionStatus.RUNNING, Set.of(ExecutionStatus.WAITING), contextData, null, null);
	}

	public FlowExecution cancel() {
		return to(ExecutionStatus.CANCELLED, Set.of(ExecutionStatus.WAITING), contextData, null, null);
	}

	public FlowExecution timeOut(Map<String, Object> error) {
		return to(ExecutionStatus.TIMED_OUT, Set.of(ExecutionStatus.WAITING), contextData, null, error);
	}

	public FlowExecution complete(Map<String, Object> context, Map<String, Object> result) {
		return to(ExecutionStatus.COMPLETED, Set.of(ExecutionStatus.RUNNING), context, result, null);
	}

	public FlowExecution fail(Map<String, Object> context, String code, String message) {
		return fail(context, code, message, false, Map.of());
	}

	/** Error shape mirrors ErrorResponse(code, retryable, details). */
	public FlowExecution fail(Map<String, Object> context, String code, String message, boolean retryable,
			Map<String, Object> details) {
		return to(ExecutionStatus.FAILED, Set.of(ExecutionStatus.PENDING, ExecutionStatus.RUNNING), context, null,
				Map.of("code", code, "message", message, "retryable", retryable, "details", details));
	}

	private FlowExecution to(ExecutionStatus next, Set<ExecutionStatus> allowedFrom, Map<String, Object> context,
			Map<String, Object> result, Map<String, Object> error) {
		return to(next, allowedFrom, context, result, error, null, null, null);
	}

	private FlowExecution to(ExecutionStatus next, Set<ExecutionStatus> allowedFrom, Map<String, Object> context,
			Map<String, Object> result, Map<String, Object> error, String waitNode, Instant waitDeadline,
			String waitReason) {
		if (!allowedFrom.contains(status)) {
			throw new IllegalStateException("illegal execution transition " + status + " -> " + next);
		}
		boolean terminal = next != ExecutionStatus.RUNNING && next != ExecutionStatus.WAITING;
		return new FlowExecution(id, tenantId, flowDefinitionId, flowKey, flowVersion, snapshot, correlationId, next,
				inputData, context, result, error, lockVersion, startedAt, terminal ? Instant.now() : null,
				parentExecutionId, parentNodeId, waitNode, waitDeadline, waitReason);
	}

}
