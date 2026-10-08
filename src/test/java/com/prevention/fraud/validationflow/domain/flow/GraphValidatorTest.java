package com.prevention.fraud.validationflow.domain.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

class GraphValidatorTest {

	final GraphValidator validator = new GraphValidator("doc-check"::equals);

	static Map<String, Object> node(String type, Map<String, Object> config, String... targets) {
		return new HashMap<>(Map.of("type", type, "config", config,
				"transitions", java.util.Arrays.stream(targets).map(t -> Map.of("to", t)).toList()));
	}

	/** start -> check -> end, valid. */
	static Map<String, Object> valid() {
		Map<String, Object> nodes = new HashMap<>();
		nodes.put("start", node("START", Map.of(), "check"));
		nodes.put("check", node("VALIDATION", new HashMap<>(Map.of("validatorType", "doc-check")), "end"));
		nodes.put("end", node("END", Map.of()));
		return new HashMap<>(Map.of("schemaVersion", 1, "startNodeId", "start", "nodes", nodes));
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> nodes(Map<String, Object> g) {
		return (Map<String, Object>) g.get("nodes");
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> cfg(Map<String, Object> g, String id) {
		return (Map<String, Object>) ((Map<String, Object>) nodes(g).get(id)).get("config");
	}

	void expect(String code, Consumer<Map<String, Object>> mutate) {
		Map<String, Object> g = valid();
		mutate.accept(g);
		List<String> codes = validator.validate(g).stream().map(GraphValidator.GraphError::code).toList();
		assertTrue(codes.contains(code), code + " expected in " + codes);
	}

	@Test
	void validGraphPasses() {
		assertEquals(List.of(), validator.validate(valid()));
	}

	@Test
	void missingStartNodeId() {
		expect("MISSING_START", g -> g.remove("startNodeId"));
	}

	@Test
	void startNodeNotOfTypeStart() {
		expect("MISSING_START", g -> g.put("startNodeId", "check"));
	}

	@Test
	void noPathToEnd() {
		expect("NO_PATH_TO_END", g -> nodes(g).put("check", node("VALIDATION", Map.of("validatorType", "doc-check"))));
	}

	@Test
	void edgeToUnknownNode() {
		expect("EDGE_TO_UNKNOWN_NODE", g -> nodes(g).put("check",
				node("VALIDATION", Map.of("validatorType", "doc-check"), "end", "ghost")));
	}

	@Test
	void cycle() {
		expect("CYCLE_DETECTED", g -> nodes(g).put("check",
				node("VALIDATION", Map.of("validatorType", "doc-check"), "end", "check")));
	}

	@Test
	void unreachableNode() {
		expect("UNREACHABLE_NODE", g -> nodes(g).put("orphan", node("END", Map.of())));
	}

	@Test
	void unknownValidatorType() {
		expect("UNKNOWN_VALIDATOR_TYPE", g -> cfg(g, "check").put("validatorType", "nope"));
	}

	@Test
	void invalidTimeout() {
		expect("INVALID_TIMEOUT", g -> cfg(g, "check").put("timeout", "30 seconds"));
		expect("INVALID_TIMEOUT", g -> cfg(g, "check").put("timeout", "PT0S"));
		assertEquals(List.of(), okWith(c -> c.put("timeout", "PT30S")));
	}

	@Test
	void invalidRetryPolicy() {
		expect("INVALID_RETRY_POLICY", g -> cfg(g, "check").put("retryPolicy", Map.of("maxAttempts", 0)));
		expect("INVALID_RETRY_POLICY",
				g -> cfg(g, "check").put("retryPolicy", Map.of("maxAttempts", 2, "backoff", "RANDOM")));
		assertEquals(List.of(),
				okWith(c -> c.put("retryPolicy", Map.of("maxAttempts", 3, "backoff", "EXPONENTIAL"))));
	}

	@Test
	void retryAndTimeoutLimits() {
		expect("INVALID_RETRY_POLICY", g -> cfg(g, "check").put("retryPolicy", Map.of("maxAttempts", 2_000_000_000)));
		expect("INVALID_RETRY_POLICY",
				g -> cfg(g, "check").put("retryPolicy", Map.of("maxAttempts", 70, "backoff", "EXPONENTIAL")));
		expect("INVALID_RETRY_POLICY",
				g -> cfg(g, "check").put("retryPolicy", Map.of("maxAttempts", 2, "delay", "PT24H")));
		expect("INVALID_RETRY_POLICY", g -> cfg(g, "check").put("retryPolicy", Map.of("maxAttempts", 2, "delay", "abc")));
		expect("INVALID_RETRY_POLICY", g -> cfg(g, "check").put("retryPolicy", Map.of("maxAttempts", 2, "delay", "PT-1S")));
		expect("INVALID_TIMEOUT", g -> cfg(g, "check").put("timeout", "PT1H"));
		assertEquals(List.of(), okWith(c -> c.put("retryPolicy", Map.of("maxAttempts", 10, "delay", "PT1M"))));
		assertEquals(List.of(), okWith(c -> c.put("timeout", "PT5M")));
	}

	@Test
	void invalidCondition() {
		for (Object bad : List.of(Map.of("operator", "LIKE", "field", "a", "value", 1),
				Map.of("operator", "EQUALS", "value", 1), Map.of("operator", "IN", "field", "a", "value", "x"),
				Map.of("operator", "NOT", "conditions", List.of()),
				Map.of("operator", "AND", "conditions", List.of(Map.of("operator", "EXISTS")))))
			expect("INVALID_CONDITION", g -> withCondition(g, bad));
		Map<String, Object> g = valid();
		withCondition(g, Map.of("operator", "AND", "conditions", List.of(Map.of("operator", "EXISTS", "field", "a"),
				Map.of("operator", "IN", "field", "b", "value", List.of(1)))));
		assertEquals(List.of(), validator.validate(g));
	}

	@Test
	void invalidMapping() {
		expect("INVALID_MAPPING", g -> addSubFlow(g, Map.of("flowKey", "child", "inputMapping", Map.of("a", "plain"))));
		assertEquals(List.of(), validator.validate(withSubFlow(Map.of("flowKey", "child", "inputMapping",
				Map.of("a", "$.x"), "outputMapping", Map.of("b", "$.y")))));
	}

	@Test
	void subFlowDepthAboveLimit() {
		expect("SUB_FLOW_DEPTH_EXCEEDED", g -> addSubFlow(g, Map.of("flowKey", "child", "maxDepth", 99)));
		expect("INVALID_SUB_FLOW", g -> addSubFlow(g, Map.of("flowKey", "child", "onFailure", "IGNORE")));
		assertEquals(List.of(), validator.validate(withSubFlow(Map.of("flowKey", "child", "maxDepth", 2))));
	}

	@SuppressWarnings("unchecked")
	static void withCondition(Map<String, Object> g, Object condition) {
		((Map<String, Object>) nodes(g).get("start")).put("transitions",
				List.of(Map.of("to", "check", "condition", condition)));
	}

	List<GraphValidator.GraphError> okWith(Consumer<Map<String, Object>> c) {
		Map<String, Object> g = valid();
		c.accept(cfg(g, "check"));
		return validator.validate(g);
	}

	static void addSubFlow(Map<String, Object> g, Map<String, Object> config) {
		nodes(g).put("sub", node("SUB_FLOW", config, "end"));
		@SuppressWarnings("unchecked")
		Map<String, Object> start = (Map<String, Object>) nodes(g).get("start");
		start.put("transitions", List.of(Map.of("to", "check"), Map.of("to", "sub")));
	}

	Map<String, Object> withSubFlow(Map<String, Object> config) {
		Map<String, Object> g = valid();
		addSubFlow(g, config);
		return g;
	}

	@Test
	void documentGroupsShapeAndPlacement() {
		Map<String, Object> ok = Map.of("name", "g", "items", List.of(Map.of("document", "CPF"), Map.of("oneOf", List.of("RG", "CNH"))));
		Consumer<Map<String, Object>> onDecision = g -> {
			nodes(g).put("dec", node("DECISION", new HashMap<>(Map.of("params", Map.of("documentGroups", List.of(ok)))), "end"));
			withTransition(g, "dec");
		};
		Map<String, Object> g = valid();
		onDecision.accept(g);
		assertEquals(List.of(), validator.validate(g));
		expect("INVALID_DOCUMENT_GROUP", x -> cfg(x, "check").put("params", Map.of("documentGroups", List.of(ok))));
		for (Object bad : List.of(List.of(), List.of(Map.of("name", "g", "items", List.of(Map.of("oneOf", List.of("RG"))))),
				List.of(Map.of("name", "g", "items", List.of(Map.of("document", "A", "oneOf", List.of("B", "C"))))),
				List.of(ok, ok))) {
			expect("INVALID_DOCUMENT_GROUP", x -> {
				nodes(x).put("dec", node("DECISION", new HashMap<>(Map.of("params", Map.of("documentGroups", bad))), "end"));
				withTransition(x, "dec");
			});
		}
	}

	@SuppressWarnings("unchecked")
	static void withTransition(Map<String, Object> g, String to) {
		((Map<String, Object>) nodes(g).get("start")).put("transitions", List.of(Map.of("to", "check"), Map.of("to", to)));
	}


	@Test
	void graphAboveNodeOrTransitionLimitIsTooLarge() {
		var small = new GraphValidator("doc-check"::equals, 3, 2);
		assertTrue(small.validate(valid()).isEmpty());
		Map<String, Object> g = valid();
		nodes(g).put("extra", node("END", Map.of()));
		assertEquals("GRAPH_TOO_LARGE", small.validate(g).get(0).code());
		assertEquals("GRAPH_TOO_LARGE", new GraphValidator("doc-check"::equals, 10, 1).validate(valid()).get(0).code());
	}

}
