package com.prevention.fraud.validationflow.adapter.out.postgres;

import com.prevention.fraud.validationflow.application.execution.recovery.RecoveryService;
import com.prevention.fraud.validationflow.domain.flow.FlowDefinition;
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

import com.prevention.fraud.validationflow.application.execution.ExecutionService;
import com.prevention.fraud.validationflow.application.execution.node.ValidatorRunner;
import com.prevention.fraud.validationflow.config.MicrometerExecutionMetrics;
import com.prevention.fraud.validationflow.application.flow.FlowException;
import com.prevention.fraud.validationflow.application.flow.FlowService;
import com.prevention.fraud.validationflow.application.validator.ValidatorRegistry;
import com.prevention.fraud.validationflow.application.validator.ValidatorException;
import com.prevention.fraud.validationflow.application.validator.ValidatorStrategy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.prevention.fraud.validationflow.domain.execution.ExecutionStatus;
import com.prevention.fraud.validationflow.domain.execution.FlowExecution;
import com.prevention.fraud.validationflow.domain.flow.GraphValidator;
import com.prevention.fraud.validationflow.domain.flow.InputField;

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

	static com.prevention.fraud.validationflow.application.execution.ports.ExecutionRepository exRepo;

	static com.prevention.fraud.validationflow.application.execution.ports.IdempotencyRepository idemRepo;

	static com.prevention.fraud.validationflow.application.flow.ports.FlowRepository flowRepo;

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

	/** Fails with the credential in the message: a ValidatorException (known value replaced) or a RuntimeException. */
	static ValidatorStrategy leakingFailure(String key, boolean domainException) {
		return new ValidatorStrategy() {
			public String key() {
				return key;
			}

			public ValidationResult execute(ValidationInput in) {
				String token = (String) ((Map<?, ?>) in.data().get("inputData")).get("token");
				if (domainException) {
					throw new ValidatorException("BAD_TOKEN", "rejected " + token, false);
				}
				throw new IllegalStateException("boom " + token);
			}
		};
	}

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
		var registry = new ValidatorRegistry(List.of(FAKE, FLAKY, SLOW, LEAKY, HANGING,
				leakingFailure("leak-domain", true), leakingFailure("leak-runtime", false)));
		var ctx = JpaTestContext.start(pg);
		flowRepo = JpaTestContext.flowRepository(ctx);
		exRepo = JpaTestContext.executionRepository(ctx);
		idemRepo = JpaTestContext.idempotencyRepository(ctx);
		flows = new FlowService(flowRepo, new GraphValidator(registry::contains));
		executions = new ExecutionService(flows, exRepo, registry, new ValidatorRunner(new MicrometerExecutionMetrics(meters), 64, 64));
	}

	static UUID activate(String key, String ctx, Map<String, Object> g, List<InputField> contract) {
		var d = flows.createDraft("t", "t",
				new FlowService.CreateFlow(key, "PF", ctx, "d", null, g, contract, null));
		flows.activate("t", d.id());
		return d.id();
	}

	@Test
	void credentialsNeverReachAnyTableOrLog() {
		String token = "SECRET-TOKEN-123";
		var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
		appender.start();
		var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ExecutionService.class);
		logger.addAppender(appender);
		try {
			for (String validator : List.of("leak-domain", "leak-runtime")) {
				Map<String, Object> exists = Map.of("operator", "EXISTS", "field", "inputData.token");
				Map<String, Object> g = Map.of("startNodeId", "s", "nodes", Map.of(
						"s", Map.of("type", "START", "transitions", List.of(Map.of("to", "v", "condition",
								Map.of("operator", "AND", "conditions", List.of(exists,
										Map.of("operator", "NOT", "conditions", List.of(exists))))), Map.of("to", "v"))),
						"v", Map.of("type", "VALIDATION", "config", Map.of("validatorType", validator),
								"transitions", List.of(Map.of("to", "e"))),
						"e", Map.of("type", "END")));
				activate("mask-" + validator, "CM-" + validator, g, List.of());
				FlowExecution f = executions.execute("t", null, "PF", "CM-" + validator,
						Map.of("token", token, "nested", Map.of("apiKey", "NESTED-KEY-9")), null);
				assertEquals(ExecutionStatus.FAILED, f.status());
				assertFalse(f.errorInfo().toString().contains(token), f.errorInfo().toString());
				if (validator.equals("leak-runtime")) {
					assertEquals("unexpected error", f.errorInfo().get("message"));
				}
				assertEquals("***", jdbc.queryForObject("SELECT input_data->>'token' FROM flow_execution WHERE id = ?",
						String.class, f.id()));
			}
		}
		finally {
			logger.detachAppender(appender);
		}
		var tables = jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
				String.class);
		for (String table : tables) {
			for (String secret : List.of(token, "NESTED-KEY-9")) {
				assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM \"" + table + "\" t WHERE t::text LIKE ?",
						Integer.class, "%" + secret + "%"), table);
			}
		}
		assertTrue(appender.list.stream().noneMatch(e -> e.getFormattedMessage().contains(token)
				|| e.getThrowableProxy() != null));
		assertTrue(jdbc.queryForObject("SELECT count(*) FROM execution_audit_log WHERE details::text LIKE '%***%'",
				Integer.class) > 0);
	}

	@Test
	void pausingHandlerParksExecutionAndIdempotentReplayReturnsIt() {
		var pausing = new ExecutionService(flows, exRepo,
				List.of(new com.prevention.fraud.validationflow.application.execution.node.NodeHandler() {
					public String type() {
						return "START";
					}

					public FlowExecution handle(com.prevention.fraud.validationflow.application.execution.node.NodeStep step) {
						return step.pause("needs a person", java.time.Instant.parse("2030-01-01T00:00:00Z"));
					}
				}), new MicrometerExecutionMetrics(meters));
		activate("k-pause", "CP", graph(true, "v1"), List.of());
		var idem = new com.prevention.fraud.validationflow.application.execution.idempotency.IdempotentExecutionService(
				pausing, idemRepo, java.time.Duration.ofMinutes(1));
		var first = idem.executeIdempotent("t", "pause-key", "k-pause", "PF", "CP", Map.of(), null);
		FlowExecution f = first.execution();
		assertEquals(ExecutionStatus.WAITING, f.status());
		assertEquals("needs a person", f.waitReason());
		assertEquals("s", f.waitingNodeId());
		assertEquals(java.time.Instant.parse("2030-01-01T00:00:00Z"), f.waitDeadline());
		assertEquals(null, f.completedAt());
		assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM node_execution WHERE execution_id = ?",
				String.class, f.id()));
		var replay = idem.executeIdempotent("t", "pause-key", "k-pause", "PF", "CP", Map.of(), null);
		assertTrue(replay.replayed());
		assertEquals(f.id(), replay.execution().id());
		assertEquals(ExecutionStatus.WAITING, replay.execution().status());
	}

	@Test
	void humanApprovalParksExecutionWithReasonGroupAndDeadline() {
		Map<String, Object> g = Map.of("startNodeId", "s", "nodes", Map.of(
				"s", Map.of("type", "START", "transitions", List.of(Map.of("to", "h"))),
				"h", Map.of("type", "HUMAN_APPROVAL", "config", Map.of("reason", "manual review", "assignedGroup", "risk",
						"deadline", "PT1H"), "transitions", List.of(Map.of("to", "e", "label", "APPROVED"),
						Map.of("to", "e", "label", "REJECTED"))),
				"e", Map.of("type", "END")));
		activate("k-human", "CHUMAN", g, List.of());
		FlowExecution f = executions.execute("t", null, "PF", "CHUMAN", Map.of(), null);
		assertEquals(ExecutionStatus.WAITING, f.status());
		assertEquals("h", f.waitingNodeId());
		assertEquals("manual review", f.waitReason());
		assertTrue(f.waitDeadline().isAfter(java.time.Instant.now().plusSeconds(3000)));
		assertEquals("risk", jdbc.queryForObject(
				"SELECT output_data->>'assignedGroup' FROM node_execution WHERE execution_id = ? AND node_id = 'h'",
				String.class, f.id()));
	}

	@Test
	void resumeFollowsDecisionBranchOnceAndOnlyForTheTenant() throws Exception {
		Map<String, Object> g = Map.of("startNodeId", "s", "nodes", Map.of(
				"s", Map.of("type", "START", "transitions", List.of(Map.of("to", "h"))),
				"h", Map.of("type", "HUMAN_APPROVAL", "config", Map.of("reason", "review", "deadline", "PT1H"),
						"transitions", List.of(Map.of("to", "ok", "label", "APPROVED"),
								Map.of("to", "no", "label", "REJECTED"))),
				"ok", Map.of("type", "END", "config", Map.of("tag", "ok")),
				"no", Map.of("type", "END", "config", Map.of("tag", "no"))));
		activate("k-resume", "CRESUME", g, List.of());
		double resumedBefore = meters.counter("validation.execution.resumed").count();
		var approved = executions.execute("t", null, "PF", "CRESUME", Map.of(), null);
		assertThrows(FlowException.class, () -> executions.resume("other", approved.id(), "APPROVED", null, null, "x"));
		var done = executions.resume("t", approved.id(), "APPROVED", "fine", Map.of("ticket", "T-1"), "alice");
		assertEquals(ExecutionStatus.COMPLETED, done.status());
		assertEquals("ok", ((Map<?, ?>) done.result().get("config")).get("tag"));
		assertEquals("alice", jdbc.queryForObject(
				"SELECT output_data->>'decidedBy' FROM node_execution WHERE execution_id = ? AND node_id = 'h' AND attempt = 2",
				String.class, approved.id()));
		assertEquals(1, jdbc.queryForObject(
				"SELECT count(*) FROM execution_audit_log WHERE execution_id = ? AND event_type = 'EXECUTION_RESUMED' AND details->>'note' = 'fine'",
				Integer.class, approved.id()));
		var nodesBefore = executions.nodes("t", approved.id()).size();
		assertThrows(FlowException.class, () -> executions.resume("t", approved.id(), "APPROVED", null, null, "alice"));
		assertEquals(nodesBefore, executions.nodes("t", approved.id()).size());

		var rejected = executions.execute("t", null, "PF", "CRESUME", Map.of(), null);
		var r = executions.resume("t", rejected.id(), "REJECTED", null, null, "bob");
		assertEquals("no", ((Map<?, ?>) r.result().get("config")).get("tag"));

		var racing = executions.execute("t", null, "PF", "CRESUME", Map.of(), null);
		var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
		var go = new java.util.concurrent.CountDownLatch(1);
		List<java.util.concurrent.Future<Boolean>> fs = new java.util.ArrayList<>();
		for (int i = 0; i < 2; i++) {
			fs.add(pool.submit(() -> {
				go.await();
				try {
					executions.resume("t", racing.id(), "APPROVED", null, null, "c");
					return true;
				}
				catch (FlowException e) {
					return false;
				}
			}));
		}
		go.countDown();
		int wins = 0;
		for (var f : fs) {
			wins += f.get() ? 1 : 0;
		}
		pool.shutdown();
		assertEquals(1, wins);
		assertEquals(resumedBefore + 3, meters.counter("validation.execution.resumed").count()); // losers and 409s do not count
	}

	@Test
	void cancelOnlyWaitingAndOnlyForTheTenant() {
		Map<String, Object> g = Map.of("startNodeId", "s", "nodes", Map.of(
				"s", Map.of("type", "START", "transitions", List.of(Map.of("to", "h"))),
				"h", Map.of("type", "HUMAN_APPROVAL", "config", Map.of("reason", "review", "deadline", "PT1H"),
						"transitions", List.of(Map.of("to", "e", "label", "APPROVED"),
								Map.of("to", "e", "label", "REJECTED"))),
				"e", Map.of("type", "END")));
		activate("k-cancel", "CCANCEL", g, List.of());
		var w = executions.execute("t", null, "PF", "CCANCEL", Map.of(), null);
		assertThrows(FlowException.class, () -> executions.cancel("other", w.id(), null, "x"));
		double cancelledBefore = meters.counter("validation.execution.cancelled").count();
		var c = executions.cancel("t", w.id(), "no longer needed", "alice");
		assertEquals(cancelledBefore + 1, meters.counter("validation.execution.cancelled").count());
		assertEquals(ExecutionStatus.CANCELLED, c.status());
		assertTrue(c.completedAt() != null);
		assertEquals("no longer needed", c.errorInfo().get("reason"));
		assertEquals(1, jdbc.queryForObject(
				"SELECT count(*) FROM execution_audit_log WHERE execution_id = ? AND event_type = 'EXECUTION_CANCELLED' AND details->>'cancelledBy' = 'alice'",
				Integer.class, w.id()));
		assertThrows(FlowException.class, () -> executions.cancel("t", w.id(), null, "alice"));
		assertThrows(FlowException.class, () -> executions.resume("t", w.id(), "APPROVED", null, null, "alice"));
	}

	@Test
	void handlersAreResolvedByTypeAndUnknownTypeFails() {
		var ran = new java.util.concurrent.atomic.AtomicBoolean();
		var startOnly = new ExecutionService(flows, exRepo,
				List.of(new com.prevention.fraud.validationflow.application.execution.node.NodeHandler() {
					public String type() {
						return "START";
					}

					public FlowExecution handle(com.prevention.fraud.validationflow.application.execution.node.NodeStep step) {
						ran.set(true);
						return null;
					}
				}), new MicrometerExecutionMetrics(meters));
		activate("k-handler", "CH", graph(true, "v1"), List.of());
		FlowExecution f = startOnly.execute("t", null, "PF", "CH", Map.of(), null);
		assertTrue(ran.get());
		assertEquals(ExecutionStatus.FAILED, f.status());
		assertEquals("UNSUPPORTED_NODE_TYPE", f.errorInfo().get("code"));
	}

	@Test
	void executionCounterAndTimersTrackCompletedAndFailed() {
		double completed = count("COMPLETED"), failed = count("FAILED");
		activate("k-met-ok", "CMO", graph(true, "v1"), List.of());
		activate("k-met-bad", "CMB", graph(false, "v1"), List.of());
		executions.execute("t", null, "PF", "CMO", Map.of("ok", true), null);
		executions.execute("t", null, "PF", "CMB", Map.of("ok", false), null);
		assertEquals(completed + 1, count("COMPLETED"));
		assertEquals(failed + 1, count("FAILED"));
		assertTrue(meters.get("validation.execution.duration").tag("status", "COMPLETED").timer().count() >= 1);
		assertTrue(meters.get("validation.node.duration").tag("type", "VALIDATION").timer().count() >= 1);
	}

	private static double count(String status) {
		var c = meters.find("validation.execution").tag("status", status).counter();
		return c == null ? 0 : c.count();
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
	void saturatedTimeoutPoolRejectsInsteadOfGrowing() {
		var release = new java.util.concurrent.CountDownLatch(1);
		var stubborn = new ValidatorStrategy() {
			public String key() {
				return "stubborn";
			}

			public ValidationResult execute(ValidationInput in) {
				while (true) { // ignores interrupt until released
					try {
						if (release.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
							return new ValidationResult(true, Map.of());
						}
					}
					catch (InterruptedException ignored) {
					}
				}
			}
		};
		var registry = new ValidatorRegistry(List.of(stubborn));
		var ownFlows = new FlowService(flowRepo,
				new GraphValidator(registry::contains));
		var small = new ExecutionService(ownFlows, exRepo,
				registry, new ValidatorRunner(new MicrometerExecutionMetrics(meters), 2, 1));
		var g = singleNodeGraph("stubborn", Map.of("timeout", "PT0.05S"));
		ownFlows.activate("t", ownFlows.createDraft("t", "t", new FlowService.CreateFlow("k-stub", "PF", "CSTUB", "d", null,
				g, List.of(), null)).id());
		try {
			var codes = new java.util.ArrayList<Object>();
			long before = Thread.getAllStackTraces().keySet().stream()
					.filter(th -> th.getName().equals("validator-timeout")).count(); // other tests' pool
			for (int i = 0; i < 6; i++) {
				codes.add(small.execute("t", null, "PF", "CSTUB", Map.of(), null).errorInfo().get("code"));
				long threads = Thread.getAllStackTraces().keySet().stream()
						.filter(th -> th.getName().equals("validator-timeout")).count();
				assertTrue(threads <= before + 2, "threads=" + threads);
			}
			assertEquals(List.of("NODE_TIMEOUT", "NODE_TIMEOUT", "NODE_TIMEOUT", "NODE_REJECTED", "NODE_REJECTED",
					"NODE_REJECTED"), codes);
			assertEquals(3.0, meters.counter("validation.node.rejected", "validator", "stubborn").count());
		}
		finally {
			release.countDown();
		}
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
	void resumingChildContinuesParentAndParentIdIsRejected() {
		Map<String, Object> approval = Map.of("startNodeId", "s", "nodes", Map.of(
				"s", Map.of("type", "START", "transitions", List.of(Map.of("to", "h"))),
				"h", Map.of("type", "HUMAN_APPROVAL", "config", Map.of("reason", "review", "deadline", "PT1H"),
						"transitions", List.of(Map.of("to", "ok", "label", "APPROVED"), Map.of("to", "no", "label", "REJECTED"))),
				"ok", Map.of("type", "END", "config", Map.of("tag", "ok")),
				"no", Map.of("type", "END", "config", Map.of("tag", "no"))));
		activate("r-c", "RC", approval, List.of());
		activate("r-b", "RB", subGraph("r-c", Map.of("outputMapping", Map.of("tag", "$.nodes.ok.tag"))), List.of());
		activate("r-a", "RA", subGraph("r-b", Map.of()), List.of());
		for (String decision : List.of("APPROVED", "REJECTED")) {
			FlowExecution a = executions.execute("t", "r-a", null, null, Map.of(), null);
			String childId = jdbc.queryForObject("SELECT id::text FROM flow_execution WHERE flow_key = 'r-c' AND status = 'WAITING'",
					String.class);
			var e = assertThrows(FlowException.class, () -> executions.resume("t", a.id(), "APPROVED", null, null, "x"));
			assertTrue(e.getMessage().contains("waiting on sub-flow execution"));
			var c = executions.resume("t", java.util.UUID.fromString(childId), decision, null, null, "alice");
			assertEquals(ExecutionStatus.COMPLETED, c.status());
			assertEquals("APPROVED".equals(decision) ? "ok" : "no", ((Map<?, ?>) c.result().get("config")).get("tag"));
			assertEquals(ExecutionStatus.COMPLETED, executions.get("t", a.id()).status());
			assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM flow_execution WHERE status = 'WAITING' AND flow_key LIKE 'r-%'", Integer.class));
			assertThrows(FlowException.class, () -> executions.resume("t", java.util.UUID.fromString(childId), decision, null, null, "alice"));
		}
	}

	@Test
	void pausedChildLeavesParentAndGrandparentWaiting() {
		Map<String, Object> approval = Map.of("startNodeId", "s", "nodes", Map.of(
				"s", Map.of("type", "START", "transitions", List.of(Map.of("to", "h"))),
				"h", Map.of("type", "HUMAN_APPROVAL", "config", Map.of("reason", "review", "deadline", "PT1H"),
						"transitions", List.of(Map.of("to", "e", "label", "APPROVED"), Map.of("to", "e", "label", "REJECTED"))),
				"e", Map.of("type", "END", "config", Map.of())));
		activate("w-c", "WC", approval, List.of());
		activate("w-b", "WB", subGraph("w-c", Map.of()), List.of());
		activate("w-a", "WA", subGraph("w-b", Map.of()), List.of());
		FlowExecution a = executions.execute("t", "w-a", null, null, Map.of(), null);
		assertEquals(ExecutionStatus.WAITING, a.status());
		assertEquals("sub", a.waitingNodeId());
		assertEquals("SUB_FLOW_WAITING", a.waitReason());
		var rows = jdbc.queryForList("SELECT id, flow_key, status, waiting_node_id, wait_deadline, parent_node_id "
				+ "FROM flow_execution WHERE flow_key IN ('w-b', 'w-c') ORDER BY flow_key");
		assertEquals(2, rows.size());
		rows.forEach(r -> assertEquals("WAITING", r.get("status")));
		assertEquals("sub", rows.get(0).get("parent_node_id"));
		assertEquals("sub", rows.get(0).get("waiting_node_id"));
		assertEquals("h", rows.get(1).get("waiting_node_id"));
		assertEquals(rows.get(1).get("wait_deadline"), rows.get(0).get("wait_deadline"));
		assertEquals(a.waitDeadline().toEpochMilli() / 1000, jdbc.queryForObject("SELECT floor(extract(epoch FROM wait_deadline))::bigint FROM flow_execution WHERE id = ?", Long.class, a.id()));
		assertEquals(rows.get(1).get("id").toString(), jdbc.queryForObject(
				"SELECT output_data->>'childExecutionId' FROM node_execution WHERE execution_id = ? AND node_id = 'sub'",
				String.class, rows.get(0).get("id")));
		assertEquals("PENDING", jdbc.queryForObject(
				"SELECT status FROM node_execution WHERE execution_id = ? AND node_id = 'sub'", String.class, a.id()));
	}

	private void pausedChain(String prefix) {
		Map<String, Object> approval = Map.of("startNodeId", "s", "nodes", Map.of(
				"s", Map.of("type", "START", "transitions", List.of(Map.of("to", "h"))),
				"h", Map.of("type", "HUMAN_APPROVAL", "config", Map.of("reason", "review", "deadline", "PT1H"),
						"transitions", List.of(Map.of("to", "e", "label", "APPROVED"), Map.of("to", "e", "label", "REJECTED"))),
				"e", Map.of("type", "END", "config", Map.of())));
		activate(prefix + "-c", prefix + "C", approval, List.of());
		activate(prefix + "-b", prefix + "B", subGraph(prefix + "-c", Map.of()), List.of());
		activate(prefix + "-a", prefix + "A", subGraph(prefix + "-b", Map.of()), List.of());
		assertEquals(ExecutionStatus.WAITING, executions.execute("t", prefix + "-a", null, null, Map.of(), null).status());
	}

	private java.util.UUID idOf(String flowKey) {
		return jdbc.queryForObject("SELECT id FROM flow_execution WHERE flow_key = ?", java.util.UUID.class, flowKey);
	}

	private List<String> statuses(String prefix) {
		return jdbc.queryForList("SELECT status || ':' || coalesce(error_info->>'reason', error_info->>'code', '') "
				+ "FROM flow_execution WHERE flow_key LIKE ? ORDER BY flow_key", String.class, prefix + "-%");
	}

	@Test
	void cancellingEitherSideCancelsTheOtherAndAuditsBoth() {
		pausedChain("cc1");
		executions.cancel("t", idOf("cc1-a"), "stop", "alice");
		assertEquals(List.of("CANCELLED:stop", "CANCELLED:PARENT_CANCELLED", "CANCELLED:PARENT_CANCELLED"), statuses("cc1"));
		assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM execution_audit_log WHERE event_type = 'EXECUTION_CANCELLED' "
				+ "AND execution_id IN (SELECT id FROM flow_execution WHERE flow_key LIKE 'cc1-%')", Integer.class));
		pausedChain("cc2");
		executions.cancel("t", idOf("cc2-c"), null, "alice");
		assertEquals(List.of("CANCELLED:CHILD_CANCELLED", "CANCELLED:CHILD_CANCELLED", "CANCELLED:"), statuses("cc2"));
		// a finished execution is never altered
		assertThrows(FlowException.class, () -> executions.cancel("t", idOf("cc2-a"), "again", "alice"));
	}

	@Test
	void expiringEitherSideExpiresTheOther() {
		pausedChain("ee1");
		var meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
		var clock = java.time.Clock.fixed(java.time.Instant.now().plusSeconds(7200), java.time.ZoneOffset.UTC);
		var svc = new com.prevention.fraud.validationflow.application.execution.recovery.WaitExpiryService(exRepo, clock,
				new MicrometerExecutionMetrics(meters));
		svc.expire();
		assertEquals(List.of("TIMED_OUT:WAIT_EXPIRED", "TIMED_OUT:WAIT_EXPIRED", "TIMED_OUT:WAIT_EXPIRED"), statuses("ee1"));
		assertTrue(meters.counter("validation.execution.wait_expired").count() >= 3); // other tests may leave overdue rows
		assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM execution_audit_log WHERE event_type = 'EXECUTION_WAIT_EXPIRED' "
				+ "AND execution_id IN (SELECT id FROM flow_execution WHERE flow_key LIKE 'ee1-%')", Integer.class));
		// only the child is overdue: the parents follow it
		pausedChain("ee2");
		jdbc.update("UPDATE flow_execution SET wait_deadline = now() + interval '1 day' WHERE flow_key IN ('ee2-a', 'ee2-b')");
		svc.expire();
		assertEquals(List.of("TIMED_OUT:WAIT_EXPIRED", "TIMED_OUT:WAIT_EXPIRED", "TIMED_OUT:WAIT_EXPIRED"), statuses("ee2"));
	}

	@Test
	void recoveryOfAbandonedChildNeverLeavesWaitingParent() {
		pausedChain("rr");
		// the child was resumed (RUNNING) and the JVM died before it finished
		jdbc.update("UPDATE flow_execution SET status = 'RUNNING', started_at = now() - interval '1 day' WHERE flow_key = 'rr-c'");
		new RecoveryService(exRepo, idemRepo, java.time.Duration.ofHours(1)).recover();
		assertEquals(List.of("CANCELLED:CHILD_ABANDONED", "CANCELLED:CHILD_ABANDONED", "FAILED:EXECUTION_ABANDONED"), statuses("rr"));
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM flow_execution p WHERE p.status = 'WAITING' AND NOT EXISTS "
				+ "(SELECT 1 FROM flow_execution c WHERE c.parent_execution_id = p.id AND c.status = 'WAITING') "
				+ "AND p.flow_key LIKE 'rr-%'", Integer.class));
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

	@Test
	void recoveryFailsStaleRunningAcrossTenantsAndDropsOrphanClaims() {
		var flowT = flows.get("t", activate("rec-t", "CR-t", graph(true, "x"), List.of()));
		var du = flows.createDraft("u", "u", new FlowService.CreateFlow("rec-u", "PF", "CR-u", "d", null,
				graph(true, "x"), List.of(), null));
		flows.activate("u", du.id());
		var flowU = flows.get("u", du.id());
		var old = java.time.Instant.now().minus(java.time.Duration.ofHours(3));
		FlowExecution staleT = running("t", flowT, old), staleU = running("u", flowU, old),
				fresh = running("t", flowT, java.time.Instant.now());
		jdbc.update("INSERT INTO idempotency_key (tenant_id, idempotency_key, request_hash, locked_until) "
				+ "VALUES ('t', 'orphan-old', 'h', now() - interval '1 minute'), ('t', 'orphan-live', 'h', now() + interval '1 hour')");

		assertEquals(2, new com.prevention.fraud.validationflow.application.execution.recovery.RecoveryService(exRepo, idemRepo,
				java.time.Duration.ofHours(1)).recover());

		for (var ex : List.of(staleT, staleU)) {
			var got = executions.get(ex.tenantId(), ex.id());
			assertEquals(ExecutionStatus.FAILED, got.status());
			assertEquals("EXECUTION_ABANDONED", got.errorInfo().get("code"));
			assertTrue(got.completedAt() != null);
			assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM execution_audit_log WHERE tenant_id = ? "
					+ "AND execution_id = ? AND event_type = 'EXECUTION_ABANDONED'", Integer.class, ex.tenantId(), ex.id()));
		}
		assertEquals(ExecutionStatus.RUNNING, executions.get("t", fresh.id()).status());
		assertThrows(FlowException.class, () -> executions.get("t", staleU.id())); // no cross-tenant leak
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM idempotency_key WHERE idempotency_key = 'orphan-old'", Integer.class));
		assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM idempotency_key WHERE idempotency_key = 'orphan-live'", Integer.class));

		// an execution finished meanwhile is not overwritten: the stale read loses on lock_version
		FlowExecution late = running("t", flowT, old);
		jdbc.update("UPDATE flow_execution SET status = 'COMPLETED', lock_version = 1 WHERE id = ?", late.id());
		assertFalse(exRepo.update(late.fail(Map.of(), "X", "x")));
		assertEquals(ExecutionStatus.COMPLETED, executions.get("t", late.id()).status());
	}

	@Test
	void recoveryIgnoresWaitingAndPersistsWaitColumns() {
		var flow = flows.get("t", activate("rec-w", "CR-w", graph(true, "x"), List.of()));
		var old = java.time.Instant.now().minus(java.time.Duration.ofHours(3));
		var r = running("t", flow, old);
		var deadline = java.time.Instant.now().plus(java.time.Duration.ofDays(1)).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
		assertTrue(exRepo.update(r.waitAt("n1", deadline, "needs approval", Map.of())));

		exRepo.findRunningStartedBefore(java.time.Instant.now());
		new RecoveryService(exRepo, idemRepo, java.time.Duration.ofHours(1)).recover();

		var got = executions.get("t", r.id());
		assertEquals(ExecutionStatus.WAITING, got.status());
		assertEquals("n1", got.waitingNodeId());
		assertEquals("needs approval", got.waitReason());
		assertEquals(deadline, got.waitDeadline());
		assertEquals(null, got.completedAt());
	}

	@Test
	void waitExpiryTimesOutOnlyExpiredWaitingAndLosesToResume() {
		var flow = flows.get("t", activate("exp-k", "CEXP", graph(true, "x"), List.of()));
		var base = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
		var expired = running("t", flow, base);
		var future = running("t", flow, base);
		var raced = running("t", flow, base);
		var stillRunning = running("t", flow, base);
		assertTrue(exRepo.update(expired.waitAt("n1", base.plusSeconds(10), "r", Map.of())));
		assertTrue(exRepo.update(future.waitAt("n1", base.plusSeconds(1000), "r", Map.of())));
		var racedWaiting = raced.waitAt("n1", base.plusSeconds(10), "r", Map.of());
		assertTrue(exRepo.update(racedWaiting));
		var clock = java.time.Clock.fixed(base.plusSeconds(60), java.time.ZoneOffset.UTC);
		// a resume wins the race after the expiry job has read the row
		var svc = new com.prevention.fraud.validationflow.application.execution.recovery.WaitExpiryService(
				new com.prevention.fraud.validationflow.application.execution.ports.ExecutionRepository() {
					public java.util.List<FlowExecution> findWaitingExpiredBefore(java.time.Instant now) {
						var read = exRepo.findWaitingExpiredBefore(now);
						jdbc.update("UPDATE flow_execution SET status = 'RUNNING', lock_version = lock_version + 1 WHERE id = ?",
								raced.id());
						return read;
					}

					public void insert(FlowExecution e) { exRepo.insert(e); }

					public boolean update(FlowExecution e) { return exRepo.update(e); }

					public java.util.Optional<FlowExecution> findById(String t, java.util.UUID id) { return exRepo.findById(t, id); }

					public void recordNode(String t, java.util.UUID id, String n, String ty, int a, String st, Map<String, Object> o,
							Map<String, Object> er, Map<String, Object> in, java.time.Instant at) {
						exRepo.recordNode(t, id, n, ty, a, st, o, er, in, at);
					}

					public java.util.List<com.prevention.fraud.validationflow.domain.execution.NodeExecution> findNodes(String t, java.util.UUID id) {
						return exRepo.findNodes(t, id);
					}

					public java.util.List<FlowExecution> list(String t, int l, long o) { return exRepo.list(t, l, o); }

					public long count(String t) { return exRepo.count(t); }

					public long countWaiting() { return exRepo.countWaiting(); }

					public java.util.List<FlowExecution> findWaitingChildren(String t, java.util.UUID p) { return exRepo.findWaitingChildren(t, p); }

					public void audit(String t, java.util.UUID id, String ev, String n, Map<String, Object> d) { exRepo.audit(t, id, ev, n, d); }

					public java.util.List<FlowExecution> findRunningStartedBefore(java.time.Instant c) { return exRepo.findRunningStartedBefore(c); }
				}, clock, new MicrometerExecutionMetrics(meters));

		double expiredBefore = meters.counter("validation.execution.wait_expired").count();
		assertEquals(1, svc.expire());
		assertEquals(expiredBefore + 1, meters.counter("validation.execution.wait_expired").count());
		assertEquals(jdbc.queryForObject("SELECT count(*) FROM flow_execution WHERE status = 'WAITING'", Long.class).doubleValue(),
				meters.get("validation.execution.waiting").gauge().value());

		var got = executions.get("t", expired.id());
		assertEquals(ExecutionStatus.TIMED_OUT, got.status());
		assertEquals("WAIT_EXPIRED", got.errorInfo().get("code"));
		assertTrue(got.completedAt() != null);
		assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM execution_audit_log WHERE execution_id = ? AND event_type = 'EXECUTION_WAIT_EXPIRED'",
				Integer.class, expired.id()));
		assertEquals(ExecutionStatus.WAITING, executions.get("t", future.id()).status());
		assertEquals(ExecutionStatus.RUNNING, executions.get("t", raced.id()).status());
		assertEquals(ExecutionStatus.RUNNING, executions.get("t", stillRunning.id()).status());
	}

	static FlowExecution running(String tenant, com.prevention.fraud.validationflow.domain.flow.FlowDefinition flow,
			java.time.Instant startedAt) {
		var p = FlowExecution.pending(tenant, flow, null, Map.of());
		var r = new FlowExecution(p.id(), tenant, p.flowDefinitionId(), p.flowKey(), p.flowVersion(), p.snapshot(), null,
				ExecutionStatus.RUNNING, Map.of(), Map.of(), null, null, 0, startedAt, null, null, null);
		exRepo.insert(r);
		return r;
	}

}
