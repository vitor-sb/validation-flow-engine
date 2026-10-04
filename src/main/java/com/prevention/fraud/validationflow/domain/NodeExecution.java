package com.prevention.fraud.validationflow.domain;

import java.time.Instant;
import java.util.Map;

/** One attempt of one node in an execution. */
public record NodeExecution(String nodeId, String nodeType, int attempt, String status, Map<String, Object> inputSnapshot,
		Map<String, Object> outputData, Map<String, Object> errorInfo, Instant startedAt, Instant completedAt) {
}
