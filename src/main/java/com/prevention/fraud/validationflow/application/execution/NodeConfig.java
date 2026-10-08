package com.prevention.fraud.validationflow.application.execution;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Typed view of a node's {@code config} in the snapshot. {@code raw} is the untouched dynamic config, still what
 * validators receive and what the END node persists.
 */
record NodeConfig(Duration timeout, String validatorType, RetryPolicy retryPolicy, Integer maxDepth, String flowKey,
		Map<String, String> inputMapping, Map<String, String> outputMapping, List<Map<String, Object>> documentGroups,
		Map<String, Object> raw) {

	/** {@code backoffExponential}: delay doubles per attempt (capped by the runner) instead of staying fixed. */
	record RetryPolicy(int maxAttempts, Duration delay, boolean backoffExponential) {
	}

	static NodeConfig from(Map<String, Object> raw) {
		Map<String, Object> policy = Maps.of(raw.get("retryPolicy"));
		RetryPolicy retry = new RetryPolicy(policy.get("maxAttempts") instanceof Integer n ? n : 1,
				Duration.parse((String) policy.getOrDefault("delay", "PT0.1S")),
				"EXPONENTIAL".equals(policy.get("backoff")));
		Object groups = Maps.of(raw.get("params")).get("documentGroups");
		return new NodeConfig(raw.get("timeout") == null ? null : Duration.parse((String) raw.get("timeout")),
				(String) raw.get("validatorType"), retry, raw.get("maxDepth") instanceof Integer d ? d : null,
				(String) raw.get("flowKey"), Maps.strings(raw.get("inputMapping")), Maps.strings(raw.get("outputMapping")),
				groups == null ? null : Maps.list(groups), raw);
	}

}
