package com.prevention.fraud.validationflow.domain;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Structural validation of a flow graph: {@code startNodeId}, {@code nodes} by id, each node with
 * {@code type}, {@code config} and ordered {@code transitions[{to, condition}]}. Pure, no persistence.
 */
public class GraphValidator {

	public record GraphError(String code, String nodeId, String message) {
	}

	public static final int MAX_SUB_FLOW_DEPTH = 3;
	private static final Set<String> NODE_TYPES = Set.of("START", "VALIDATION", "DECISION", "SUB_FLOW", "END");
	private static final Set<String> COMPARISONS = Set.of("EQUALS", "NOT_EQUALS", "GREATER_THAN",
			"GREATER_THAN_OR_EQUALS", "LESS_THAN", "LESS_THAN_OR_EQUALS", "CONTAINS", "IN", "EXISTS");

	private final Predicate<String> validatorExists;

	public GraphValidator(Predicate<String> validatorExists) {
		this.validatorExists = validatorExists;
	}

	public List<GraphError> validate(Map<String, Object> graph) {
		List<GraphError> errors = new ArrayList<>();
		Map<String, Map<String, Object>> nodes = nodes(graph);
		Object start = graph == null ? null : graph.get("startNodeId");
		if (nodes.isEmpty()) {
			errors.add(err("NO_NODES", null, "graph has no nodes"));
		}
		if (!(start instanceof String s) || s.isBlank() || !nodes.containsKey(s)
				|| !"START".equals(nodes.get(s).get("type"))) {
			errors.add(err("MISSING_START", null, "startNodeId must reference a node of type START"));
			start = null;
		}

		Map<String, List<String>> edges = new HashMap<>();
		nodes.forEach((id, node) -> {
			Object type = node.get("type");
			if (!NODE_TYPES.contains(type)) {
				errors.add(err("INVALID_NODE_TYPE", id, "unknown node type " + type));
			}
			Map<String, Object> config = map(node.get("config"));
			checkConfig(id, type, config, errors);
			List<String> targets = new ArrayList<>();
			for (Object t : list(node.get("transitions"))) {
				Map<String, Object> tr = map(t);
				Object to = tr.get("to");
				if (!(to instanceof String target) || !nodes.containsKey(target)) {
					errors.add(err("EDGE_TO_UNKNOWN_NODE", id, "transition targets missing node " + to));
				}
				else {
					targets.add(target);
				}
				if (tr.containsKey("condition") && !validCondition(tr.get("condition"))) {
					errors.add(err("INVALID_CONDITION", id, "invalid condition"));
				}
			}
			edges.put(id, targets);
		});

		if (start != null) {
			Set<String> reached = reach((String) start, edges);
			nodes.keySet().stream().filter(id -> !reached.contains(id))
					.forEach(id -> errors.add(err("UNREACHABLE_NODE", id, "node is not reachable from start")));
			if (reached.stream().noneMatch(id -> "END".equals(nodes.get(id).get("type")))) {
				errors.add(err("NO_PATH_TO_END", null, "no path from start to an END node"));
			}
		}
		// ponytail: cycles are always rejected (DAG only in the MVP)
		if (hasCycle(edges)) {
			errors.add(err("CYCLE_DETECTED", null, "graph contains a cycle"));
		}
		return errors;
	}

	private void checkConfig(String id, Object type, Map<String, Object> config, List<GraphError> errors) {
		if ("VALIDATION".equals(type)) {
			Object vt = config.get("validatorType");
			if (!(vt instanceof String v) || !validatorExists.test(v)) {
				errors.add(err("UNKNOWN_VALIDATOR_TYPE", id, "validatorType not registered: " + vt));
			}
		}
		if (config.containsKey("timeout")) {
			try {
				if (!(config.get("timeout") instanceof String t) || Duration.parse(t).compareTo(Duration.ZERO) <= 0) {
					throw new IllegalArgumentException();
				}
			}
			catch (RuntimeException e) {
				errors.add(err("INVALID_TIMEOUT", id, "timeout must be a positive ISO-8601 duration"));
			}
		}
		Object groups = map(config.get("params")).get("documentGroups");
		if (groups != null && (!"DECISION".equals(type) || !DocumentGroups.valid(groups, GraphValidator::validCondition))) {
			errors.add(err("INVALID_DOCUMENT_GROUP", id, "params.documentGroups is only allowed on DECISION nodes, as a list of "
					+ "{name (unique), condition?, items[{document | oneOf (2+), condition?}]}"));
		}
		if (config.containsKey("retryPolicy")) {
			Map<String, Object> rp = map(config.get("retryPolicy"));
			if (!(rp.get("maxAttempts") instanceof Integer n) || n < 1
					|| (rp.containsKey("backoff") && !Set.of("FIXED", "EXPONENTIAL").contains(rp.get("backoff")))) {
				errors.add(err("INVALID_RETRY_POLICY", id, "maxAttempts must be >= 1 and backoff FIXED or EXPONENTIAL"));
			}
		}
		if ("SUB_FLOW".equals(type)) {
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
			if (depth != null && (!(depth instanceof Integer d) || d < 1 || d > MAX_SUB_FLOW_DEPTH)) {
				errors.add(err("SUB_FLOW_DEPTH_EXCEEDED", id, "maxDepth must be 1.." + MAX_SUB_FLOW_DEPTH));
			}
		}
	}

	private static boolean validMapping(Object m) {
		return m instanceof Map<?, ?> map && map.entrySet().stream().allMatch(e -> e.getKey() instanceof String k
				&& !k.isBlank() && e.getValue() instanceof String v && v.startsWith("$"));
	}

	private static boolean validCondition(Object c) {
		if (!(c instanceof Map<?, ?> m) || !(m.get("operator") instanceof String op)) {
			return false;
		}
		if (op.equals("AND") || op.equals("OR") || op.equals("NOT")) {
			List<Object> subs = list(m.get("conditions"));
			return !subs.isEmpty() && (!op.equals("NOT") || subs.size() == 1)
					&& subs.stream().allMatch(GraphValidator::validCondition);
		}
		if (!COMPARISONS.contains(op) || !(m.get("field") instanceof String f) || f.isBlank()) {
			return false;
		}
		if (op.equals("EXISTS")) {
			return true;
		}
		return m.containsKey("value") && (!op.equals("IN") || m.get("value") instanceof List<?>);
	}

	private static Set<String> reach(String start, Map<String, List<String>> edges) {
		Set<String> seen = new HashSet<>(Set.of(start));
		Deque<String> todo = new ArrayDeque<>(List.of(start));
		while (!todo.isEmpty()) {
			for (String next : edges.getOrDefault(todo.pop(), List.of())) {
				if (seen.add(next)) {
					todo.push(next);
				}
			}
		}
		return seen;
	}

	private static boolean hasCycle(Map<String, List<String>> edges) {
		Map<String, Integer> state = new HashMap<>(); // 1 = in progress, 2 = done
		return edges.keySet().stream().anyMatch(id -> cyclic(id, edges, state));
	}

	private static boolean cyclic(String id, Map<String, List<String>> edges, Map<String, Integer> state) {
		Integer s = state.get(id);
		if (s != null) {
			return s == 1;
		}
		state.put(id, 1);
		for (String next : edges.getOrDefault(id, List.of())) {
			if (cyclic(next, edges, state)) {
				return true;
			}
		}
		state.put(id, 2);
		return false;
	}

	private static Map<String, Map<String, Object>> nodes(Map<String, Object> graph) {
		Map<String, Map<String, Object>> out = new java.util.LinkedHashMap<>();
		if (graph != null) {
			map(graph.get("nodes")).forEach((k, v) -> out.put(k, map(v)));
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object o) {
		return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
	}

	@SuppressWarnings("unchecked")
	private static List<Object> list(Object o) {
		return o instanceof List<?> l ? (List<Object>) l : List.of();
	}

	private static GraphError err(String code, String nodeId, String message) {
		return new GraphError(code, nodeId, message);
	}

}
