package com.prevention.fraud.validationflow.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One run of a flow over the snapshot of the version resolved at start; state changes only through the domain methods. */
public record FlowExecution(UUID id, String tenantId, UUID flowDefinitionId, String flowKey, int flowVersion,
		Map<String, Object> snapshot, String correlationId, ExecutionStatus status, Map<String, Object> inputData,
		Map<String, Object> contextData, Map<String, Object> result, Map<String, Object> errorInfo,
		long lockVersion, Instant startedAt, Instant completedAt) {

	public static FlowExecution pending(String tenantId, FlowDefinition flow, String correlationId,
			Map<String, Object> inputData) {
		return new FlowExecution(UUID.randomUUID(), tenantId, flow.id(), flow.flowKey(), flow.version(),
				flow.graphDefinition(), correlationId, ExecutionStatus.PENDING, inputData, Map.of(), null, null, 0,
				Instant.now(), null);
	}

	public FlowExecution start() {
		return to(ExecutionStatus.RUNNING, Set.of(ExecutionStatus.PENDING), contextData, null, null);
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
		if (!allowedFrom.contains(status)) {
			throw new IllegalStateException("illegal execution transition " + status + " -> " + next);
		}
		boolean terminal = next != ExecutionStatus.RUNNING;
		return new FlowExecution(id, tenantId, flowDefinitionId, flowKey, flowVersion, snapshot, correlationId, next,
				inputData, context, result, error, lockVersion, startedAt, terminal ? Instant.now() : null);
	}

}
