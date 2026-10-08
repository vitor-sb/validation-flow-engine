package com.prevention.fraud.validationflow.domain.flow;

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

	public static final int DEFAULT_MAX_NODES = 200;
	public static final int DEFAULT_MAX_TRANSITIONS = 1000;
	public static final int DEFAULT_MAX_ATTEMPTS = 10;
	public static final Duration DEFAULT_MAX_DELAY = Duration.ofMinutes(1);
	public static final Duration DEFAULT_MAX_TIMEOUT = Duration.ofMinutes(5);

	private final NodeConfigRules configRules;
	private final int maxNodes;
	private final int maxTransitions;

	public GraphValidator(Predicate<String> validatorExists) {
		this(validatorExists, DEFAULT_MAX_NODES, DEFAULT_MAX_TRANSITIONS);
	}

	public GraphValidator(Predicate<String> validatorExists, int maxNodes, int maxTransitions) {
		this(validatorExists, maxNodes, maxTransitions, DEFAULT_MAX_ATTEMPTS, DEFAULT_MAX_DELAY, DEFAULT_MAX_TIMEOUT);
	}

	public GraphValidator(Predicate<String> validatorExists, int maxNodes, int maxTransitions, int maxAttempts,
			Duration maxDelay, Duration maxTimeout) {
		this.configRules = new NodeConfigRules(validatorExists, maxAttempts, maxDelay, maxTimeout);
		this.maxNodes = maxNodes;
		this.maxTransitions = maxTransitions;
	}

	/** Size limits only (cheap); also used on create/update, where drafts may still be structurally invalid. */
	public List<GraphError> checkSize(Map<String, Object> graph) {
		Map<String, Map<String, Object>> nodes = nodes(graph);
		long transitions = nodes.values().stream().mapToLong(n -> list(n.get("transitions")).size()).sum();
		if (nodes.size() > maxNodes || transitions > maxTransitions) {
			return List.of(err("GRAPH_TOO_LARGE", null, "graph has " + nodes.size() + " nodes and " + transitions
					+ " transitions; limits are " + maxNodes + " and " + maxTransitions));
		}
		return List.of();
	}

	public List<GraphError> validate(Map<String, Object> graph) {
		List<GraphError> tooLarge = checkSize(graph);
		if (!tooLarge.isEmpty()) {
			return tooLarge; // don't walk an oversized graph
		}
		List<GraphError> errors = new ArrayList<>();
		Map<String, Map<String, Object>> nodes = nodes(graph);
		String start = checkStart(graph, nodes, errors);
		Map<String, List<String>> edges = new HashMap<>();
		nodes.forEach((id, node) -> edges.put(id, checkNode(id, node, nodes, errors)));
		if (start != null) {
			checkReachability(start, nodes, edges, errors);
		}
		// ponytail: cycles are always rejected (DAG only in the MVP)
		if (hasCycle(edges)) {
			errors.add(err("CYCLE_DETECTED", null, "graph contains a cycle"));
		}
		return errors;
	}

	/** Returns the start node id, or null (with an error recorded) when it is not a valid START node. */
	private static String checkStart(Map<String, Object> graph, Map<String, Map<String, Object>> nodes,
			List<GraphError> errors) {
		Object start = graph == null ? null : graph.get("startNodeId");
		if (nodes.isEmpty()) {
			errors.add(err("NO_NODES", null, "graph has no nodes"));
		}
		if (!(start instanceof String s) || s.isBlank() || !nodes.containsKey(s)
				|| !"START".equals(nodes.get(s).get("type"))) {
			errors.add(err("MISSING_START", null, "startNodeId must reference a node of type START"));
			return null;
		}
		return s;
	}

	/** Checks type, config and transitions of one node; returns its valid transition targets. */
	private List<String> checkNode(String id, Map<String, Object> node, Map<String, Map<String, Object>> nodes,
			List<GraphError> errors) {
		Object type = node.get("type");
		if (!NODE_TYPES.contains(type)) {
			errors.add(err("INVALID_NODE_TYPE", id, "unknown node type " + type));
		}
		configRules.check(id, type, map(node.get("config")), errors);
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
			if (tr.containsKey("condition") && !NodeConfigRules.validCondition(tr.get("condition"))) {
				errors.add(err("INVALID_CONDITION", id, "invalid condition"));
			}
		}
		return targets;
	}

	private static void checkReachability(String start, Map<String, Map<String, Object>> nodes,
			Map<String, List<String>> edges, List<GraphError> errors) {
		Set<String> reached = reach(start, edges);
		nodes.keySet().stream().filter(id -> !reached.contains(id))
				.forEach(id -> errors.add(err("UNREACHABLE_NODE", id, "node is not reachable from start")));
		if (reached.stream().noneMatch(id -> "END".equals(nodes.get(id).get("type")))) {
			errors.add(err("NO_PATH_TO_END", null, "no path from start to an END node"));
		}
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
	static Map<String, Object> map(Object o) {
		return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
	}

	@SuppressWarnings("unchecked")
	static List<Object> list(Object o) {
		return o instanceof List<?> l ? (List<Object>) l : List.of();
	}

	private static GraphError err(String code, String nodeId, String message) {
		return new GraphError(code, nodeId, message);
	}

}
