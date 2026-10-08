package com.prevention.fraud.validationflow.domain.flow;

import static com.prevention.fraud.validationflow.domain.flow.GraphValidator.list;
import static com.prevention.fraud.validationflow.domain.flow.GraphValidator.map;

import com.prevention.fraud.validationflow.domain.execution.DocumentGroups;
import com.prevention.fraud.validationflow.domain.flow.GraphValidator.GraphError;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Per-node {@code config} rules of {@link GraphValidator}, one method per rule; error codes are the contract. */
class NodeConfigRules {

	private static final Set<String> COMPARISONS = Set.of("EQUALS", "NOT_EQUALS", "GREATER_THAN",
			"GREATER_THAN_OR_EQUALS", "LESS_THAN", "LESS_THAN_OR_EQUALS", "CONTAINS", "IN", "EXISTS");

	private final Predicate<String> validatorExists;
	private final int maxAttempts;
	private final Duration maxDelay;
	private final Duration maxTimeout;

	NodeConfigRules(Predicate<String> validatorExists, int maxAttempts, Duration maxDelay, Duration maxTimeout) {
		this.validatorExists = validatorExists;
		this.maxAttempts = maxAttempts;
		this.maxDelay = maxDelay;
		this.maxTimeout = maxTimeout;
	}

	void check(String id, Object type, Map<String, Object> config, List<GraphError> errors) {
		validatorType(id, type, config, errors);
		timeout(id, config, errors);
		documentGroups(id, type, config, errors);
		retryPolicy(id, config, errors);
		if ("SUB_FLOW".equals(type)) {
			subFlow(id, config, errors);
		}
	}

	private void validatorType(String id, Object type, Map<String, Object> config, List<GraphError> errors) {
		if ("VALIDATION".equals(type)) {
			Object vt = config.get("validatorType");
			if (!(vt instanceof String v) || !validatorExists.test(v)) {
				errors.add(err("UNKNOWN_VALIDATOR_TYPE", id, "validatorType not registered: " + vt));
			}
		}
	}

	private void timeout(String id, Map<String, Object> config, List<GraphError> errors) {
		if (config.containsKey("timeout")) {
			try {
				if (!(config.get("timeout") instanceof String t) || Duration.parse(t).compareTo(Duration.ZERO) <= 0
						|| Duration.parse(t).compareTo(maxTimeout) > 0) {
					throw new IllegalArgumentException();
				}
			}
			catch (RuntimeException e) {
				errors.add(err("INVALID_TIMEOUT", id, "timeout must be a positive ISO-8601 duration of at most " + maxTimeout));
			}
		}
	}

	private void documentGroups(String id, Object type, Map<String, Object> config, List<GraphError> errors) {
		Object groups = map(config.get("params")).get("documentGroups");
		if (groups != null && (!"DECISION".equals(type) || !DocumentGroups.valid(groups, NodeConfigRules::validCondition))) {
			errors.add(err("INVALID_DOCUMENT_GROUP", id, "params.documentGroups is only allowed on DECISION nodes, as a list of "
					+ "{name (unique), condition?, items[{document | oneOf (2+), condition?}]}"));
		}
	}

	private void retryPolicy(String id, Map<String, Object> config, List<GraphError> errors) {
		if (config.containsKey("retryPolicy")) {
			Map<String, Object> rp = map(config.get("retryPolicy"));
			if (!(rp.get("maxAttempts") instanceof Integer n) || n < 1 || n > maxAttempts
					|| (rp.containsKey("backoff") && !Set.of("FIXED", "EXPONENTIAL").contains(rp.get("backoff")))
					|| (rp.containsKey("delay") && !validDelay(rp.get("delay")))) {
				errors.add(err("INVALID_RETRY_POLICY", id, "maxAttempts must be 1.." + maxAttempts
						+ ", backoff FIXED or EXPONENTIAL, delay an ISO-8601 duration of 0.." + maxDelay));
			}
		}
	}

	private void subFlow(String id, Map<String, Object> config, List<GraphError> errors) {
		if (!(config.get("flowKey") instanceof String k) || k.isBlank()) {
			errors.add(err("INVALID_SUB_FLOW", id, "SUB_FLOW requires flowKey"));
		}
		for (String m : List.of("inputMapping", "outputMapping")) {
			if (config.containsKey(m) && !validMapping(config.get(m))) {
				errors.add(err("INVALID_MAPPING", id, m + " must map names to JSONPath ('$...') strings"));
			}
		}
		if (config.containsKey("onFailure") && !"FAIL_PARENT".equals(config.get("onFailure"))) {
			errors.add(err("INVALID_SUB_FLOW", id, "onFailure must be FAIL_PARENT"));
		}
		Object depth = config.get("maxDepth");
		if (depth != null && (!(depth instanceof Integer d) || d < 1 || d > GraphValidator.MAX_SUB_FLOW_DEPTH)) {
			errors.add(err("SUB_FLOW_DEPTH_EXCEEDED", id, "maxDepth must be 1.." + GraphValidator.MAX_SUB_FLOW_DEPTH));
		}
	}

	private boolean validDelay(Object d) {
		try {
			Duration v = Duration.parse((String) d);
			return !v.isNegative() && v.compareTo(maxDelay) <= 0;
		}
		catch (RuntimeException e) {
			return false;
		}
	}

	private static boolean validMapping(Object m) {
		return m instanceof Map<?, ?> map && map.entrySet().stream().allMatch(e -> e.getKey() instanceof String k
				&& !k.isBlank() && e.getValue() instanceof String v && v.startsWith("$"));
	}

	static boolean validCondition(Object c) {
		if (!(c instanceof Map<?, ?> m) || !(m.get("operator") instanceof String op)) {
			return false;
		}
		if (op.equals("AND") || op.equals("OR") || op.equals("NOT")) {
			List<Object> subs = list(m.get("conditions"));
			return !subs.isEmpty() && (!op.equals("NOT") || subs.size() == 1)
					&& subs.stream().allMatch(NodeConfigRules::validCondition);
		}
		if (!COMPARISONS.contains(op) || !(m.get("field") instanceof String f) || f.isBlank()) {
			return false;
		}
		if (op.equals("EXISTS")) {
			return true;
		}
		return m.containsKey("value") && (!op.equals("IN") || m.get("value") instanceof List<?>);
	}

	private static GraphError err(String code, String nodeId, String message) {
		return new GraphError(code, nodeId, message);
	}

}
