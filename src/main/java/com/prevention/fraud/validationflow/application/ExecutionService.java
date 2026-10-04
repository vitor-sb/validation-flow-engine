package com.prevention.fraud.validationflow.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.prevention.fraud.validationflow.domain.ConditionEvaluator;
import com.prevention.fraud.validationflow.domain.FlowDefinition;
import com.prevention.fraud.validationflow.domain.FlowExecution;
import com.prevention.fraud.validationflow.domain.NodeExecution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import io.micrometer.core.instrument.MeterRegistry;

public class ExecutionService {

	private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

	private final FlowService flows;

	private final ExecutionRepository repository;

	private final ValidatorRegistry registry;

	private final MeterRegistry meters;

	// only used when a node has config.timeout; daemon threads so a hung validator never blocks shutdown
	private final ExecutorService timeoutPool = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "validator-timeout");
		t.setDaemon(true);
		return t;
	});

	public ExecutionService(FlowService flows, ExecutionRepository repository, ValidatorRegistry registry,
			MeterRegistry meters) {
		this.flows = flows;
		this.repository = repository;
		this.registry = registry;
		this.meters = meters;
	}

	/** Synchronous: resolves the active flow, validates the input contract, walks the snapshot graph, returns the final state. */
	public FlowExecution execute(String tenantId, String flowKey, String userType, String context,
			Map<String, Object> inputData, String correlationId) {
		FlowDefinition flow = flows.resolveActive(tenantId, flowKey, userType, context);
		List<String> missing = flow.inputContract().stream()
				.filter(f -> f.required() && inputData.get(f.name()) == null).map(f -> f.name()).toList();
		if (!missing.isEmpty()) {
			throw FlowException.invalidInput("missing required input field(s): " + String.join(", ", missing));
		}
		FlowExecution ex = FlowExecution.pending(tenantId, flow, correlationId, inputData);
		repository.insert(ex);
		ex = save(ex.start());
		MDC.put("correlationId", String.valueOf(correlationId));
		MDC.put("executionId", ex.id().toString());
		MDC.put("flowKey", ex.flowKey());
		MDC.put("flowVersion", String.valueOf(ex.flowVersion()));
		// fresh map per execution: nothing is shared between runs
		Map<String, Object> ctx = new HashMap<>();
		ctx.put("inputData", inputData);
		ctx.put("nodes", new LinkedHashMap<String, Object>());
		try {
			FlowExecution done = walk(ex, ctx);
			MDC.put("status", done.status().name());
			log.info("execution finished");
			return done;
		}
		catch (RuntimeException e) {
			return save(ex.fail(ctx, "EXECUTION_ERROR", String.valueOf(e.getMessage())));
		}
		finally {
			MDC.clear();
		}
	}

	@SuppressWarnings("unchecked")
	private FlowExecution walk(FlowExecution ex, Map<String, Object> ctx) {
		Map<String, Map<String, Object>> nodes = (Map<String, Map<String, Object>>) ex.snapshot().get("nodes");
		String id = (String) ex.snapshot().get("startNodeId");
		while (true) {
			Map<String, Object> node = nodes.get(id);
			String type = (String) node.get("type");
			Map<String, Object> config = node.get("config") == null ? Map.of() : (Map<String, Object>) node.get("config");
			switch (type) {
				case "START", "DECISION" -> recordNode(ex, id, type, 1, "COMPLETED", Map.of(), null, ctx, Instant.now());
				case "VALIDATION" -> {
					ValidatorStrategy v = registry.find((String) config.get("validatorType")).orElse(null);
					if (v == null) {
						return save(ex.fail(ctx, "VALIDATOR_NOT_FOUND", "validator not registered at node " + id));
					}
					Attempt a = runWithRetry(ex, id, type, v, ctx, config);
					if (a.error() != null) {
						return save(ex.fail(ctx, (String) a.error().get("code"), (String) a.error().get("message"),
								(Boolean) a.error().get("retryable"), Map.of("nodeId", id, "attempts", a.attempts())));
					}
					Map<String, Object> out = new LinkedHashMap<>(a.result().output() == null ? Map.of() : a.result().output());
					out.put("success", a.result().success());
					((Map<String, Object>) ctx.get("nodes")).put(id, out);
				}
				case "END" -> {
					recordNode(ex, id, type, 1, "COMPLETED", Map.of(), null, ctx, Instant.now());
					Map<String, Object> result = new LinkedHashMap<>();
					result.put("endNodeId", id);
					result.put("config", config);
					result.put("nodes", ctx.get("nodes"));
					return save(ex.complete(ctx, result));
				}
				default -> {
					return save(ex.fail(ctx, "UNSUPPORTED_NODE_TYPE", type + " nodes are not supported yet (node " + id + ")"));
				}
			}
			String next = null;
			for (Map<String, Object> t : (List<Map<String, Object>>) node.getOrDefault("transitions", new ArrayList<>())) {
				Map<String, Object> cond = (Map<String, Object>) t.get("condition");
				// a transition without condition is the default branch
				var ev = cond == null ? null : ConditionEvaluator.evaluate(cond, ctx);
				repository.audit(ex.tenantId(), ex.id(), "TRANSITION_EVALUATED", id, Map.of("to", t.get("to"),
						"result", ev == null || ev.result(), "observed", String.valueOf(ev == null ? null : ev.observed())));
				if (ev == null || ev.result()) {
					next = (String) t.get("to");
					break;
				}
			}
			if (next == null) {
				return save(ex.fail(ctx, "NO_MATCHING_TRANSITION", "no transition matched at node " + id));
			}
			id = next;
		}
	}

	record Attempt(ValidatorStrategy.ValidationResult result, Map<String, Object> error, int attempts) {
	}

	/** Runs the validator up to retryPolicy.maxAttempts times, one node_execution row per attempt. */
	@SuppressWarnings("unchecked")
	private Attempt runWithRetry(FlowExecution ex, String nodeId, String type, ValidatorStrategy v,
			Map<String, Object> ctx, Map<String, Object> config) {
		Map<String, Object> policy = (Map<String, Object>) config.getOrDefault("retryPolicy", Map.of());
		int max = policy.get("maxAttempts") instanceof Integer n ? n : 1;
		boolean exponential = "EXPONENTIAL".equals(policy.get("backoff"));
		Duration delay = Duration.parse((String) policy.getOrDefault("delay", "PT0.1S"));
		Duration timeout = config.get("timeout") == null ? null : Duration.parse((String) config.get("timeout"));
		for (int attempt = 1;; attempt++) {
			String status = "FAILED";
			Map<String, Object> error;
			Instant started = Instant.now();
			try {
				var r = call(v, ctx, config, timeout);
				recordNode(ex, nodeId, type, attempt, r.success() ? "COMPLETED" : "FAILED",
						r.output() == null ? Map.of() : r.output(), null, ctx, started);
				return new Attempt(r, null, attempt);
			}
			catch (TimeoutException e) {
				meters.counter("validation.node.timeout", "validator", v.key()).increment();
				status = "TIMED_OUT";
				error = error("NODE_TIMEOUT", "node " + nodeId + " exceeded " + timeout, true);
			}
			catch (ValidatorException e) {
				error = error(e.code(), String.valueOf(e.getMessage()), e.retryable());
			}
			catch (RuntimeException e) {
				error = error("VALIDATOR_ERROR", String.valueOf(e.getMessage()), false);
			}
			recordNode(ex, nodeId, type, attempt, status, Map.of(), error, ctx, started);
			meters.counter("validation.node.error", "validator", v.key(), "code", (String) error.get("code")).increment();
			if (!(Boolean) error.get("retryable") || attempt >= max) {
				return new Attempt(null, error, attempt);
			}
			meters.counter("validation.node.retry", "validator", v.key()).increment();
			long ms = delay.toMillis() * (exponential ? 1L << (attempt - 1) : 1L);
			try {
				Thread.sleep(ms);
			}
			catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				return new Attempt(null, error("INTERRUPTED", "interrupted while backing off", false), attempt);
			}
		}
	}

	private ValidatorStrategy.ValidationResult call(ValidatorStrategy v, Map<String, Object> ctx,
			Map<String, Object> config, Duration timeout) throws TimeoutException {
		var input = new ValidatorStrategy.ValidationInput(Map.copyOf(ctx), config);
		if (timeout == null) {
			return v.execute(input);
		}
		var f = timeoutPool.submit(() -> v.execute(input));
		try {
			return f.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
		}
		catch (TimeoutException e) {
			f.cancel(true);
			throw e;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
		catch (ExecutionException e) {
			throw e.getCause() instanceof RuntimeException re ? re : new IllegalStateException(e.getCause());
		}
	}

	/** Persists the attempt and logs it; payloads only appear in logs masked. */
	private void recordNode(FlowExecution ex, String nodeId, String type, int attempt, String status,
			Map<String, Object> output, Map<String, Object> error, Map<String, Object> ctx, Instant started) {
		repository.recordNode(ex.tenantId(), ex.id(), nodeId, type, attempt, status, output, error, ctx, started);
		MDC.put("nodeId", nodeId);
		MDC.put("status", status);
		try {
			log.info("node attempt={} type={} output={} error={}", attempt, type, LogMasker.mask(output),
					LogMasker.mask(error));
		}
		finally {
			MDC.remove("nodeId");
			MDC.remove("status");
		}
	}

	public FlowExecution get(String tenantId, UUID id) {
		return repository.findById(tenantId, id)
				.orElseThrow(() -> FlowException.executionNotFound());
	}

	public List<NodeExecution> nodes(String tenantId, UUID id) {
		get(tenantId, id);
		return repository.findNodes(tenantId, id);
	}

	public List<FlowExecution> list(String tenantId, int page, int size) {
		return repository.list(tenantId, size, (long) page * size);
	}

	public long count(String tenantId) {
		return repository.count(tenantId);
	}

	private static Map<String, Object> error(String code, String message, boolean retryable) {
		return Map.of("code", code, "message", message, "retryable", retryable);
	}

	/** Persists with optimistic locking and returns the instance carrying the new lock version. */
	private FlowExecution save(FlowExecution ex) {
		if (!repository.update(ex)) {
			throw new IllegalStateException("execution " + ex.id() + " was modified concurrently");
		}
		return new FlowExecution(ex.id(), ex.tenantId(), ex.flowDefinitionId(), ex.flowKey(), ex.flowVersion(),
				ex.snapshot(), ex.correlationId(), ex.status(), ex.inputData(), ex.contextData(), ex.result(),
				ex.errorInfo(), ex.lockVersion() + 1, ex.startedAt(), ex.completedAt());
	}

}
