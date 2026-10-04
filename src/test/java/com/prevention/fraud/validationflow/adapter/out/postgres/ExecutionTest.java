package com.prevention.fraud.validationflow.adapter.out.postgres;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.prevention.fraud.validationflow.application.ExecutionService;
import com.prevention.fraud.validationflow.application.FlowException;
import com.prevention.fraud.validationflow.application.FlowService;
import com.prevention.fraud.validationflow.application.ValidatorRegistry;
import com.prevention.fraud.validationflow.application.ValidatorException;
import com.prevention.fraud.validationflow.application.ValidatorStrategy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.prevention.fraud.validationflow.domain.ExecutionStatus;
import com.prevention.fraud.validationflow.domain.FlowExecution;
import com.prevention.fraud.validationflow.domain.GraphValidator;
import com.prevention.fraud.validationflow.domain.InputField;

import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class ExecutionTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16");

	static JdbcTemplate jdbc;

	static FlowService flows;

	static ExecutionService executions;

	static Runnable duringValidation = () -> { };

	/** Fake validator: succeeds iff data.inputData.ok is true; runs a hook so tests can act mid-execution. */
	static final ValidatorStrategy FAKE = new ValidatorStrategy() {
		public String key() {
			return "fake";
		}

		@SuppressWarnings("unchecked")
		public ValidationResult execute(ValidationInput in) {
			duringValidation.run();
			return new ValidationResult(Boolean.TRUE.equals(((Map<String, Object>) in.data().get("inputData")).get("ok")),
					Map.of("seen", true));
		}
	};

	static final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

	static final java.util.concurrent.atomic.AtomicBoolean retryable = new java.util.concurrent.atomic.AtomicBoolean();

	static final SimpleMeterRegistry meters = new SimpleMeterRegistry();

	/** Always throws; retryable flag is switchable. */
	static final ValidatorStrategy FLAKY = new ValidatorStrategy() {
		public String key() {
			return "flaky";
		}

		public ValidationResult execute(ValidationInput in) {
			calls.incrementAndGet();
			throw new ValidatorException("UPSTREAM_DOWN", "boom", retryable.get());
		}
	};

	static final ValidatorStrategy SLOW = new ValidatorStrategy() {
		public String key() {
			return "slow";
		}

		public ValidationResult execute(ValidationInput in) {
			calls.incrementAndGet();
			try {
				Thread.sleep(5000);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return new ValidationResult(true, Map.of());
		}
	};

	static final java.util.concurrent.atomic.AtomicInteger running = new java.util.concurrent.atomic.AtomicInteger();

	static final java.util.concurrent.atomic.AtomicInteger maxRunning = new java.util.concurrent.atomic.AtomicInteger();

	static final java.util.concurrent.atomic.AtomicInteger interrupted = new java.util.concurrent.atomic.AtomicInteger();

	/** Blocks until interrupted, tracking how many instances run at once. */
	static final ValidatorStrategy HANGING = new ValidatorStrategy() {
		public String key() {
			return "hanging";
		}

		public ValidationResult execute(ValidationInput in) {
			maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
			try {
				Thread.sleep(5000);
			}
			catch (InterruptedException e) {
				interrupted.incrementAndGet();
			}
			finally {
				running.decrementAndGet();
			}
			return new ValidationResult(true, Map.of());
		}
	};

	/** Returns a credential and a CPF in its output, and fails (retryable=false) with one in the message map. */
	static final ValidatorStrategy LEAKY = new ValidatorStrategy() {
		public String key() {
			return "leaky";
		}

		public ValidationResult execute(ValidationInput in) {
			return new ValidationResult(true, Map.of("accessToken", "out-secret", "cpf", "111.222.333-44"));
		}
	};

	static Map<String, Object> cond(String field, Object value) {
		return Map.of("operator", "EQUALS", "field", field, "value", value);
	}

	/** s -> v -> (nodes.v.success == true ? approved : denied); a second variant has no else branch. */
	static Map<String, Object> graph(boolean withElse, String endConfigTag) {
		Map<String, Object> approved = Map.of("type", "END",
				"config", Map.of("decision", "APPROVED", "tag", endConfigTag));
		Map<String, Object> toApproved = Map.of("to", "approved", "condition", cond("nodes.v.success", true));
		Map<String, Object> v = withElse
				? Map.of("type", "VALIDATION", "config", Map.of("validatorType", "fake"), "transitions",
						List.of(toApproved, Map.of("to", "denied")))
				: Map.of("type", "VALIDATION", "config", Map.of("validatorType", "fake"), "transitions",
						List.of(toApproved));
		Map<String, Object> nodes = new java.util.HashMap<>(Map.of(
				"s", Map.of("type", "START", "transitions", List.of(Map.of("to", "v"))), "v", v,
				"approved", approved));
		if (withElse) {
			nodes.put("denied", Map.of("type", "END", "config", Map.of("decision", "DENIED", "tag", endConfigTag)));
		}
		return Map.of("startNodeId", "s", "nodes", nodes);
	}

	@BeforeAll
	static void setUp() {
		Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).load().migrate();
		jdbc = new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
		var json = JsonMapper.builder().build();
		var registry = new ValidatorRegistry(List.of(FAKE, FLAKY, SLOW, LEAKY, HANGING));
		flows = new FlowService(new JdbcFlowRepository(jdbc, json), new GraphValidator(registry::contains));
		var exRepo = new JdbcExecutionRepository(jdbc, json);
		executions = new ExecutionService(flows, exRepo, registry, meters, java.time.Duration.ofHours(1));
	}

	static UUID activate(String key, String ctx, Map<String, Object> g, List<InputField> contract) {
		var d = flows.createDraft("t", "t",
				new FlowService.CreateFlow(key, "PF", ctx, "d", null, g, contract, null));
		flows.activate("t", d.id());
		return d.id();
	}

	@Test
	void sequentialAndConditionalPathsAndAudit() {
		activate("k1", "C1", graph(true, "v1"), List.of(new InputField("ok", "boolean", true)));
		FlowExecution yes = executions.execute("t", null, "PF", "C1", Map.of("ok", true), "corr-1");
		assertEquals(ExecutionStatus.COMPLETED, yes.status());
		assertEquals("APPROVED", ((Map<?, ?>) yes.result().get("config")).get("decision"));
		FlowExecution no = executions.execute("t", "k1", "PF", "C1", Map.of("ok", false), null);
		assertEquals("DENIED", ((Map<?, ?>) no.result().get("config")).get("decision"));
		assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM node_execution WHERE execution_id = ?", Integer.class, yes.id()));
		assertTrue(jdbc.queryForObject("SELECT count(*) FROM execution_audit_log WHERE execution_id = ?", Integer.class, yes.id()) >= 2);
		// PENDING->RUNNING->COMPLETED: two optimistic-lock bumps
		assertEquals(2L, jdbc.queryForObject("SELECT lock_version FROM flow_execution WHERE id = ?", Long.class, yes.id()));
	}

	@Test
	void missingRequiredFieldIsRejectedBeforeAnythingIsPersisted() {
		activate("k2", "C2", graph(true, "v1"), List.of(new InputField("ok", "boolean", true)));
		var e = assertThrows(FlowException.class, () -> executions.execute("t", null, "PF", "C2", Map.of(), null));
		assertEquals(FlowException.Kind.INVALID_INPUT, e.kind());
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM flow_execution WHERE flow_key = 'k2'", Integer.class));
	}

	@Test
	void noMatchingTransitionFailsExplicitly() {
		activate("k3", "C3", graph(false, "v1"), List.of());
		FlowExecution f = executions.execute("t", null, "PF", "C3", Map.of("ok", false), null);
		assertEquals(ExecutionStatus.FAILED, f.status());
		assertEquals("NO_MATCHING_TRANSITION", f.errorInfo().get("code"));
		assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM flow_execution WHERE id = ?", String.class, f.id()));
	}

	@Test
	void activatingNewVersionMidExecutionDoesNotAffectItAndContextIsIsolated() {
		activate("k4", "C4", graph(true, "v1"), List.of());
		duringValidation = () -> {
			var d = flows.createDraft("t", "t", new FlowService.CreateFlow("k4", "PF", "C4", "d", null,
					graph(true, "v2"), List.of(), null));
			flows.activate("t", d.id());
			duringValidation = () -> { };
		};
		FlowExecution first = executions.execute("t", null, "PF", "C4", Map.of("ok", true), null);
		assertEquals(1, first.flowVersion());
		assertEquals("v1", ((Map<?, ?>) first.result().get("config")).get("tag"));
		FlowExecution second = executions.execute("t", null, "PF", "C4", Map.of("ok", true), null);
		assertEquals(2, second.flowVersion());
		assertEquals("v2", ((Map<?, ?>) second.result().get("config")).get("tag"));
		assertFalse(first.contextData() == second.contextData());
	}

	@Test
	void illegalStateTransitionsAreRejectedByTheDomain() {
		FlowExecution done = executions.execute("t", null, "PF", "C1", Map.of("ok", true), null);
		assertThrows(IllegalStateException.class, () -> done.start());
		assertThrows(IllegalStateException.class, () -> done.fail(Map.of(), "X", "x"));
	}

	static Map<String, Object> singleNodeGraph(String validator, Map<String, Object> extra) {
		Map<String, Object> cfg = new java.util.HashMap<>(extra);
		cfg.put("validatorType", validator);
		return Map.of("startNodeId", "s", "nodes", Map.of(
				"s", Map.of("type", "START", "transitions", List.of(Map.of("to", "v"))),
				"v", Map.of("type", "VALIDATION", "config", cfg, "transitions", List.of(Map.of("to", "e"))),
				"e", Map.of("type", "END", "config", Map.of())));
	}

	@Test
	void retryableErrorIsRetriedThenFailsWithErrorInfo() {
		activate("k5", "C5", singleNodeGraph("flaky",
				Map.of("retryPolicy", Map.of("maxAttempts", 3, "backoff", "EXPONENTIAL", "delay", "PT0.01S"))), List.of());
		calls.set(0);
		meters.clear();
		retryable.set(true);
		FlowExecution f = executions.execute("t", null, "PF", "C5", Map.of(), null);
		assertEquals(3, calls.get());
		assertEquals(ExecutionStatus.FAILED, f.status());
		assertEquals("UPSTREAM_DOWN", f.errorInfo().get("code"));
		assertEquals(true, f.errorInfo().get("retryable"));
		assertEquals(List.of(1, 2, 3), jdbc.queryForList(
				"SELECT attempt FROM node_execution WHERE execution_id = ? AND node_id = 'v' ORDER BY attempt", Integer.class, f.id()));
		assertEquals(2.0, meters.counter("validation.node.retry", "validator", "flaky").count());
		assertEquals(3.0, meters.counter("validation.node.error", "validator", "flaky", "code", "UPSTREAM_DOWN").count());
	}

	@Test
	void nonRetryableErrorIsNotRetried() {
		activate("k6", "C6", singleNodeGraph("flaky", Map.of("retryPolicy", Map.of("maxAttempts", 3))), List.of());
		calls.set(0);
		retryable.set(false);
		FlowExecution f = executions.execute("t", null, "PF", "C6", Map.of(), null);
		assertEquals(1, calls.get());
		assertEquals(ExecutionStatus.FAILED, f.status());
		assertEquals(false, f.errorInfo().get("retryable"));
	}

	@Test
	void timeoutMarksAttemptTimedOutAndIsRetried() {
		activate("k7", "C7", singleNodeGraph("slow", Map.of("timeout", "PT0.05S",
				"retryPolicy", Map.of("maxAttempts", 2, "backoff", "FIXED", "delay", "PT0.01S"))), List.of());
		calls.set(0);
		FlowExecution f = executions.execute("t", null, "PF", "C7", Map.of(), null);
		assertEquals(ExecutionStatus.FAILED, f.status());
		assertEquals("NODE_TIMEOUT", f.errorInfo().get("code"));
		assertEquals(List.of("TIMED_OUT", "TIMED_OUT"), jdbc.queryForList(
				"SELECT status FROM node_execution WHERE execution_id = ? AND node_id = 'v' ORDER BY attempt", String.class, f.id()));
		assertEquals(2.0, meters.counter("validation.node.timeout", "validator", "slow").count());
	}

	@Test
	void timedOutValidatorIsInterruptedBeforeRetry() throws Exception {
		activate("k8", "C8", singleNodeGraph("hanging", Map.of("timeout", "PT0.05S",
				"retryPolicy", Map.of("maxAttempts", 2, "backoff", "FIXED", "delay", "PT0.2S"))), List.of());
		running.set(0);
		maxRunning.set(0);
		interrupted.set(0);
		executions.execute("t", null, "PF", "C8", Map.of(), null);
		assertEquals(1, maxRunning.get(), "attempts must never overlap");
		for (int i = 0; i < 50 && running.get() > 0; i++) {
			Thread.sleep(20);
		}
		assertEquals(0, running.get());
		assertEquals(2, interrupted.get());
	}

	@Test
	void nodeHistoryAuditAndTenantScopedQueries() {
		activate("k-hist", "CH", graph(true, "h"), List.of(new InputField("ok", "boolean", true)));
		FlowExecution e = executions.execute("t", "k-hist", "PF", "CH", Map.of("ok", true), "corr-h");
		var nodes = executions.nodes("t", e.id());
		assertEquals(3, nodes.size());
		assertEquals("s", nodes.get(0).nodeId());
		var v = nodes.get(1);
		assertEquals(Map.of("ok", true), ((Map<?, ?>) v.inputSnapshot().get("inputData")));
		assertEquals(1, v.attempt());
		assertTrue(v.startedAt() != null && v.completedAt() != null);
		var audit = jdbc.queryForList("SELECT details::text AS d FROM execution_audit_log WHERE execution_id = ? AND event_type = 'TRANSITION_EVALUATED'", e.id());
		assertTrue(audit.stream().anyMatch(r -> r.get("d").toString().contains("observed")));
		// tenant isolation
		assertThrows(FlowException.class, () -> executions.get("other", e.id()));
		assertThrows(FlowException.class, () -> executions.nodes("other", e.id()));
		assertEquals(0, executions.list("other", 0, 10).size());
		assertEquals(0L, executions.count("other"));
		assertTrue(executions.count("t") >= 1);
		assertTrue(executions.list("t", 0, 1).size() == 1);
	}

	@Test
	void secretsAreMaskedInPersistedSnapshotsButDocumentsAreKept() {
		var g = Map.<String, Object>of("startNodeId", "s", "nodes", Map.of(
				"s", Map.of("type", "START", "transitions", List.of(Map.of("to", "v"))),
				"v", Map.of("type", "VALIDATION", "config", Map.of("validatorType", "leaky"), "transitions",
						List.of(Map.of("to", "e"))),
				"e", Map.of("type", "END", "config", Map.of())));
		activate("k-mask", "CM", g, List.of());
		FlowExecution e = executions.execute("t", "k-mask", "PF", "CM",
				Map.of("cpf", "999.888.777-66", "password", "in-secret", "apiKey", "k-1"), "corr-m");
		var rows = jdbc.queryForList("""
				SELECT input_snapshot::text AS i, output_data::text AS o, error_info::text AS e
				FROM node_execution WHERE execution_id = ?""", e.id());
		String all = rows.toString();
		assertFalse(all.contains("in-secret") || all.contains("out-secret") || all.contains("k-1"), all);
		assertTrue(all.contains("999.888.777-66") && all.contains("111.222.333-44"), all);
		String exec = jdbc.queryForObject("SELECT context_data::text || result::text FROM flow_execution WHERE id = ?",
				String.class, e.id());
		assertFalse(exec.contains("in-secret") || exec.contains("out-secret"), exec);
	}

	static Map<String, Object> subGraph(String childKey, Map<String, Object> extra) {
		Map<String, Object> cfg = new java.util.HashMap<>(extra);
		cfg.put("flowKey", childKey);
		return Map.of("startNodeId", "s", "nodes", Map.of(
				"s", Map.of("type", "START", "transitions", List.of(Map.of("to", "sub"))),
				"sub", Map.of("type", "SUB_FLOW", "config", cfg, "transitions", List.of(Map.of("to", "e"))),
				"e", Map.of("type", "END", "config", Map.of())));
	}

	static String activationError(String key, Map<String, Object> g) {
		var d = flows.createDraft("t", "t", new FlowService.CreateFlow(key, "PF", "CTX-" + key, "d", null, g, List.of(), null));
		var e = assertThrows(FlowException.class, () -> flows.activate("t", d.id()));
		return e.errors().get(0).code();
	}

	@Test
	void subFlowRunsChildWithMappedInputAndReturnsOnlyMappedOutput() {
		activate("sf-child", "SFC", graph(true, "c"), List.of(new InputField("ok", "boolean", true)));
		activate("sf-parent", "SFP", subGraph("sf-child", Map.of("inputMapping", Map.of("ok", "$.inputData.flag"),
				"outputMapping", Map.of("seen", "$.nodes.v.seen"))), List.of());
		FlowExecution p = executions.execute("t", "sf-parent", null, null, Map.of("flag", true, "other", "x"), "corr-sf");
		assertEquals(ExecutionStatus.COMPLETED, p.status());
		var out = (Map<?, ?>) ((Map<?, ?>) p.contextData().get("nodes")).get("sub");
		assertEquals(true, out.get("seen"));
		assertFalse(out.containsKey("success") && out.containsKey("v"));
		var child = jdbc.queryForMap("SELECT parent_execution_id, parent_node_id, input_data::text AS i, correlation_id "
				+ "FROM flow_execution WHERE flow_key = 'sf-child' AND parent_execution_id = ?", p.id());
		assertEquals("sub", child.get("parent_node_id"));
		assertEquals("{\"ok\": true}", child.get("i"));
		assertEquals("corr-sf", child.get("correlation_id"));
		assertEquals(p.id(), executions.get("t", p.id()).id());
		// the parent context never saw the child's nodes
		assertFalse(((Map<?, ?>) p.contextData().get("nodes")).containsKey("v"));
	}

	@Test
	void failedChildFailsTheParentAndTenantsDoNotShareChildren() {
		activate("sf-child2", "SFC2", graph(false, "c"), List.of());
		activate("sf-parent2", "SFP2", subGraph("sf-child2", Map.of("inputMapping", Map.of("ok", "$.inputData.flag"))), List.of());
		FlowExecution p = executions.execute("t", "sf-parent2", null, null, Map.of("flag", false), null);
		assertEquals(ExecutionStatus.FAILED, p.status());
		assertEquals("SUB_FLOW_FAILED", p.errorInfo().get("code"));
		var d = flows.createDraft("other", "o", new FlowService.CreateFlow("sf-parent3", "PF", "SFP3", "d", null,
				subGraph("sf-child2", Map.of()), List.of(), null));
		flows.activate("other", d.id());
		FlowExecution o = executions.execute("other", "sf-parent3", null, null, Map.of(), null);
		assertEquals("SUB_FLOW_NOT_FOUND", o.errorInfo().get("code"));
	}

	@Test
	void compositionCyclesAreRejectedAtActivation() {
		assertEquals("SUB_FLOW_CYCLE_DETECTED", activationError("cy-self", subGraph("cy-self", Map.of())));
		activate("cy-a", "CYA", subGraph("cy-b", Map.of()), List.of()); // cy-b not active yet: allowed
		activate("cy-b", "CYB", subGraph("cy-c", Map.of()), List.of());
		assertEquals("SUB_FLOW_CYCLE_DETECTED", activationError("cy-c", subGraph("cy-a", Map.of())));
	}

	@Test
	void depthIsEnforcedAtActivation() {
		activate("d-c", "DC", singleNodeGraph("fake", Map.of()), List.of());
		activate("d-b", "DB", subGraph("d-c", Map.of("maxDepth", 1)), List.of());
		// d-c would run at depth 2 but d-b allows 1
		assertEquals("SUB_FLOW_DEPTH_EXCEEDED", activationError("d-a", subGraph("d-b", Map.of())));
	}

	@Test
	void depthIsEnforcedAtRuntime() {
		// activated root-first so each activation sees no deeper chain; the chain only exists at runtime
		activate("r-a", "RA", subGraph("r-b", Map.of()), List.of());
		activate("r-c", "RC", singleNodeGraph("fake", Map.of()), List.of());
		activate("r-b", "RB", subGraph("r-c", Map.of("maxDepth", 1)), List.of());
		FlowExecution a = executions.execute("t", "r-a", null, null, Map.of(), null);
		assertEquals(ExecutionStatus.FAILED, a.status());
		assertEquals("SUB_FLOW_FAILED", a.errorInfo().get("code"));
		var childError = (Map<?, ?>) ((Map<?, ?>) a.errorInfo().get("details")).get("childError");
		assertEquals("SUB_FLOW_DEPTH_EXCEEDED", childError.get("code"));
	}

	@Test
	void documentGroupsAreSelectedByConditionAndRecordedInTheExecution() {
		Map<String, Object> groups = Map.of("documentGroups", List.of(
				Map.of("name", "base", "items", List.of(Map.of("document", "CPF"), Map.of("oneOf", List.of("RG", "CNH")),
						Map.of("document", "PROOF", "condition", cond("inputData.rural", true)))),
				Map.of("name", "X", "condition", Map.of("operator", "CONTAINS", "field", "inputData.groups", "value", "X"),
						"items", List.of(Map.of("document", "SPECIAL")))));
		Map<String, Object> g = Map.of("startNodeId", "s", "nodes", Map.of(
				"s", Map.of("type", "START", "transitions", List.of(Map.of("to", "d"))),
				"d", Map.of("type", "DECISION", "config", Map.of("params", groups), "transitions", List.of(Map.of("to", "e"))),
				"e", Map.of("type", "END")));
		activate("dg", "DG", g, List.of());
		FlowExecution with = executions.execute("t", "dg", null, null, Map.of("groups", List.of("X"), "rural", true), null);
		assertEquals(ExecutionStatus.COMPLETED, with.status(), String.valueOf(with.errorInfo()));
		var out = (Map<?, ?>) ((Map<?, ?>) with.contextData().get("nodes")).get("d");
		assertEquals(4, ((List<?>) out.get("documents")).size()); // CPF, RG|CNH, PROOF, SPECIAL
		assertEquals(List.of(true, true), ((List<?>) out.get("groups")).stream().map(x -> ((Map<?, ?>) x).get("selected")).toList());
		assertTrue(jdbc.queryForObject("SELECT output_data::text FROM node_execution WHERE execution_id = ? AND node_id = 'd'",
				String.class, with.id()).contains("SPECIAL"));
		FlowExecution without = executions.execute("t", "dg", null, null, Map.of("groups", List.of("Y")), null);
		var out2 = (Map<?, ?>) ((Map<?, ?>) without.contextData().get("nodes")).get("d");
		assertEquals(2, ((List<?>) out2.get("documents")).size()); // CPF, RG|CNH only
		assertEquals(List.of(true, false), ((List<?>) out2.get("groups")).stream().map(x -> ((Map<?, ?>) x).get("selected")).toList());
	}

}
