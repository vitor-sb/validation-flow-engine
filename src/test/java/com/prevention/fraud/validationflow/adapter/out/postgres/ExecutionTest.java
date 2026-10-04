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
		var registry = new ValidatorRegistry(List.of(FAKE, FLAKY, SLOW));
		flows = new FlowService(new JdbcFlowRepository(jdbc, json), new GraphValidator(registry::contains));
		var exRepo = new JdbcExecutionRepository(jdbc, json);
		executions = new ExecutionService(flows, exRepo, registry, meters);
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

}
