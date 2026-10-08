package com.prevention.fraud.validationflow.adapter.out.postgres;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.prevention.fraud.validationflow.domain.execution.FlowExecution;
import com.prevention.fraud.validationflow.domain.execution.NodeExecution;

final class ExecutionMapper {

	private ExecutionMapper() {
	}

	static FlowExecutionEntity toEntity(FlowExecution f) {
		var e = new FlowExecutionEntity();
		e.id = f.id();
		e.tenantId = f.tenantId();
		e.flowDefinitionId = f.flowDefinitionId();
		e.flowKey = f.flowKey();
		e.flowVersion = f.flowVersion();
		e.flowSnapshot = f.snapshot();
		e.correlationId = f.correlationId();
		e.status = f.status();
		e.inputData = f.inputData();
		e.contextData = f.contextData();
		e.result = f.result();
		e.errorInfo = f.errorInfo();
		e.lockVersion = f.lockVersion();
		e.startedAt = f.startedAt();
		e.completedAt = f.completedAt();
		e.parentExecutionId = f.parentExecutionId();
		e.parentNodeId = f.parentNodeId();
		return e;
	}

	static FlowExecution toDomain(FlowExecutionEntity e) {
		return new FlowExecution(e.id, e.tenantId, e.flowDefinitionId, e.flowKey, e.flowVersion, e.flowSnapshot,
				e.correlationId, e.status, e.inputData, e.contextData, e.result, e.errorInfo, e.lockVersion,
				e.startedAt, e.completedAt, e.parentExecutionId, e.parentNodeId);
	}

	static NodeExecutionEntity toEntity(String tenantId, UUID executionId, String nodeId, String nodeType, int attempt,
			String status, Map<String, Object> output, Map<String, Object> error, Map<String, Object> input,
			Instant startedAt, Instant completedAt) {
		var n = new NodeExecutionEntity();
		n.id = UUID.randomUUID();
		n.tenantId = tenantId;
		n.executionId = executionId;
		n.nodeId = nodeId;
		n.nodeType = nodeType;
		n.attempt = attempt;
		n.status = status;
		n.inputSnapshot = input;
		n.outputData = output;
		n.errorInfo = error;
		n.startedAt = startedAt;
		n.completedAt = completedAt;
		return n;
	}

	static NodeExecution toDomain(NodeExecutionEntity n) {
		return new NodeExecution(n.nodeId, n.nodeType, n.attempt, n.status, n.inputSnapshot, n.outputData,
				n.errorInfo, n.startedAt, n.completedAt);
	}

}
