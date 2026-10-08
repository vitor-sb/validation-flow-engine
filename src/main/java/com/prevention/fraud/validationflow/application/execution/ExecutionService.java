package com.prevention.fraud.validationflow.application.execution;

import com.prevention.fraud.validationflow.application.execution.ports.ExecutionRepository;
import com.prevention.fraud.validationflow.application.flow.FlowException;
import com.prevention.fraud.validationflow.application.flow.FlowService;
import com.prevention.fraud.validationflow.application.masking.LogMasker;
import com.prevention.fraud.validationflow.application.validator.ValidatorRegistry;
import com.prevention.fraud.validationflow.application.validator.ValidatorStrategy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.prevention.fraud.validationflow.domain.execution.ConditionEvaluator;
import com.prevention.fraud.validationflow.domain.execution.DocumentGroups;
import com.prevention.fraud.validationflow.domain.flow.FlowDefinition;
import com.prevention.fraud.validationflow.domain.flow.GraphValidator;
import com.prevention.fraud.validationflow.domain.execution.ExecutionStatus;
import com.prevention.fraud.validationflow.domain.execution.FlowExecution;
import com.prevention.fraud.validationflow.domain.flow.GraphValidator;
import com.prevention.fraud.validationflow.domain.execution.NodeExecution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;


public class ExecutionService {

	private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

	/** Persisted instead of the message of unexpected exceptions, which may carry input data. */
	static final String UNEXPECTED = "unexpected error";

	private final FlowService flows;

	private final ExecutionRepository repository;

	private final ValidatorRegistry registry;

	private final ValidatorRunner runner;

	public ExecutionService(FlowService flows, ExecutionRepository repository, ValidatorRegistry registry,
			ValidatorRunner runner) {
		this.flows = flows;
		this.repository = repository;
		this.registry = registry;
		this.runner = runner;
	}

	/** Synchronous: resolves the active flow, validates the input contract, walks the snapshot graph, returns the final state. */
	public FlowExecution execute(String tenantId, String flowKey, String userType, String context,
			Map<String, Object> inputData, String correlationId) {
		FlowDefinition flow = flows.resolveActive(tenantId, flowKey, userType, context);
		return run(tenantId, flow, correlationId, inputData, null, null, List.of(), new HashMap<>());
	}

	/**
	 * Runs one flow; {@code chain} holds the flowKeys of the ancestors (empty for a root run) and {@code ctx} is filled
	 * with the execution context so a parent can read the child's unmasked outputs.
	 */
	private FlowExecution run(String tenantId, FlowDefinition flow, String correlationId,
			Map<String, Object> inputData, UUID parentId, String parentNodeId, List<String> chain,
			Map<String, Object> ctx) {
		List<String> missing = flow.inputContract().stream()
				.filter(f -> f.required() && inputData.get(f.name()) == null).map(f -> f.name()).toList();
		if (!missing.isEmpty()) {
			throw FlowException.invalidInput("missing required input field(s): " + String.join(", ", missing));
		}
		FlowExecution ex = FlowExecution.pending(tenantId, flow, correlationId, inputData, parentId, parentNodeId);
		repository.insert(new FlowExecution(ex.id(), ex.tenantId(), ex.flowDefinitionId(), ex.flowKey(), ex.flowVersion(),
				ex.snapshot(), ex.correlationId(), ex.status(), secrets(inputData), ex.contextData(), ex.result(),
				ex.errorInfo(), ex.lockVersion(), ex.startedAt(), ex.completedAt(), ex.parentExecutionId(),
				ex.parentNodeId()));
		ex = save(ex.start());
		var outerMdc = MDC.getCopyOfContextMap();
		MDC.put("correlationId", String.valueOf(correlationId));
		MDC.put("executionId", ex.id().toString());
		MDC.put("flowKey", ex.flowKey());
		MDC.put("flowVersion", String.valueOf(ex.flowVersion()));
		// fresh map per execution: nothing is shared between runs
		ctx.put("inputData", inputData);
		ctx.put("nodes", new LinkedHashMap<String, Object>());
		try {
			List<String> path = new ArrayList<>(chain);
			path.add(ex.flowKey());
			FlowExecution done = walk(ex, ctx, path);
			MDC.put("status", done.status().name());
			log.info("execution finished");
			return done;
		}
		catch (RuntimeException e) {
			log.warn("execution error class={} message={}", e.getClass().getName(), redact(e.getMessage(), ctx));
			return save(ex.fail(ctx, "EXECUTION_ERROR", UNEXPECTED, false, Map.of("exception", e.getClass().getName())));
		}
		finally {
			if (outerMdc == null) {
				MDC.clear();
			}
			else {
				MDC.setContextMap(outerMdc);
			}
		}
	}

	@SuppressWarnings("unchecked")
	private FlowExecution walk(FlowExecution ex, Map<String, Object> ctx, List<String> chain) {
		Map<String, Map<String, Object>> nodes = (Map<String, Map<String, Object>>) ex.snapshot().get("nodes");
		String id = (String) ex.snapshot().get("startNodeId");
		while (true) {
			Map<String, Object> node = nodes.get(id);
			String type = (String) node.get("type");
			Map<String, Object> config = node.get("config") == null ? Map.of() : (Map<String, Object>) node.get("config");
			switch (type) {
				case "START" -> recordNode(ex, id, type, 1, "COMPLETED", Map.of(), null, ctx, Instant.now());
				case "DECISION" -> {
					Object groups = ((Map<String, Object>) config.getOrDefault("params", Map.of())).get("documentGroups");
					Map<String, Object> out = groups == null ? Map.of()
							: DocumentGroups.resolve((List<Map<String, Object>>) groups, ctx);
					if (groups != null) {
						((Map<String, Object>) ctx.get("nodes")).put(id, out);
					}
					recordNode(ex, id, type, 1, "COMPLETED", out, null, ctx, Instant.now());
				}
				case "VALIDATION" -> {
					ValidatorStrategy v = registry.find((String) config.get("validatorType")).orElse(null);
					if (v == null) {
						return save(ex.fail(ctx, "VALIDATOR_NOT_FOUND", "validator not registered at node " + id));
					}
					String nodeId = id;
					var a = runner.run(nodeId, v, ctx, config,
							(att, st, out, err, t) -> recordNode(ex, nodeId, type, att, st, out, err, ctx, t));
					if (a.error() != null) {
						return save(ex.fail(ctx, (String) a.error().get("code"), (String) a.error().get("message"),
								(Boolean) a.error().get("retryable"), Map.of("nodeId", id, "attempts", a.attempts())));
					}
					Map<String, Object> out = new LinkedHashMap<>(a.result().output() == null ? Map.of() : a.result().output());
					out.put("success", a.result().success());
					((Map<String, Object>) ctx.get("nodes")).put(id, out);
				}
				case "SUB_FLOW" -> {
					FlowExecution failed = subFlow(ex, id, config, ctx, chain);
					if (failed != null) {
						return failed;
					}
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
						"result", ev == null || ev.result(),
						"observed", String.valueOf(ev == null ? null : maskObserved(cond, ev.observed()))));
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

	/**
	 * Runs the child flow (active version now) with only the mapped input and copies back only the mapped output into
	 * {@code ctx.nodes[nodeId]}. Returns the failed parent, or null to keep walking. Depth: the child runs at level
	 * {@code chain.size()} (root = 0) and may not exceed the node's maxDepth (default {@link GraphValidator#MAX_SUB_FLOW_DEPTH}).
	 */
	@SuppressWarnings("unchecked")
	private FlowExecution subFlow(FlowExecution ex, String nodeId, Map<String, Object> config,
			Map<String, Object> ctx, List<String> chain) {
		Instant started = Instant.now();
		String childKey = (String) config.get("flowKey");
		int limit = config.get("maxDepth") instanceof Integer d ? d : GraphValidator.MAX_SUB_FLOW_DEPTH;
		String code;
		String message;
		Map<String, Object> details = new LinkedHashMap<>(Map.of("nodeId", nodeId));
		if (chain.contains(childKey)) {
			code = "SUB_FLOW_CYCLE_DETECTED";
			message = "sub-flow " + childKey + " is already running in this chain " + chain;
		}
		else if (chain.size() > limit) {
			code = "SUB_FLOW_DEPTH_EXCEEDED";
			message = "sub-flow " + childKey + " would run at depth " + chain.size() + ", maxDepth is " + limit;
		}
		else {
			code = null;
			message = null;
		}
		Map<String, Object> childCtx = new HashMap<>();
		FlowExecution child = null;
		if (code == null) {
			Map<String, Object> input = new LinkedHashMap<>();
			((Map<String, String>) config.getOrDefault("inputMapping", Map.of())).forEach((name, path) -> {
				Object v = ConditionEvaluator.lookup(ctx, jsonPath(path));
				if (v != null) {
					input.put(name, v);
				}
			});
			try {
				FlowDefinition flow = flows.resolveActive(ex.tenantId(), childKey, null, null);
				child = run(ex.tenantId(), flow, ex.correlationId(), input, ex.id(), nodeId, chain, childCtx);
			}
			catch (FlowException e) {
				code = e.kind() == FlowException.Kind.INVALID_INPUT ? "SUB_FLOW_INVALID_INPUT" : "SUB_FLOW_NOT_FOUND";
				message = String.valueOf(e.getMessage());
			}
			if (child != null && child.status() != ExecutionStatus.COMPLETED) {
				code = "SUB_FLOW_FAILED"; // onFailure is always FAIL_PARENT
				message = "sub-flow " + childKey + " ended " + child.status();
				details.put("childExecutionId", child.id().toString());
				details.put("childError", child.errorInfo() == null ? Map.of() : child.errorInfo());
			}
		}
		if (code != null) {
			Map<String, Object> error = error(code, message, false);
			recordNode(ex, nodeId, "SUB_FLOW", 1, "FAILED", Map.of(), error, ctx, started);
			return save(ex.fail(ctx, code, message, false, details));
		}
		Map<String, Object> out = new LinkedHashMap<>();
		((Map<String, String>) config.getOrDefault("outputMapping", Map.of())).forEach((name, path) -> {
			Object v = ConditionEvaluator.lookup(childCtx, jsonPath(path));
			if (v != null) {
				out.put(name, v);
			}
		});
		out.put("success", true);
		out.put("childExecutionId", child.id().toString());
		((Map<String, Object>) ctx.get("nodes")).put(nodeId, out);
		recordNode(ex, nodeId, "SUB_FLOW", 1, "COMPLETED", out, null, ctx, started);
		return null;
	}

	/** Only plain dotted JSONPath ({@code $.a.b}) is supported; {@code $} alone is the whole context. */
	private static String jsonPath(String path) {
		return path.startsWith("$.") ? path.substring(2) : path.substring(1);
	}

	/** Persists the attempt and logs it; payloads only appear in logs masked. */
	@SuppressWarnings("unchecked")
	private void recordNode(FlowExecution ex, String nodeId, String type, int attempt, String status,
			Map<String, Object> output, Map<String, Object> error, Map<String, Object> ctx, Instant started) {
		error = (Map<String, Object>) redact(error, ctx);
		repository.recordNode(ex.tenantId(), ex.id(), nodeId, type, attempt, status, secrets(output), secrets(error),
				secrets(ctx), started);
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

	@SuppressWarnings("unchecked")
	private static Map<String, Object> secrets(Map<String, Object> m) {
		return m == null ? null : (Map<String, Object>) LogMasker.maskSecrets(m);
	}

	/** Replaces the credential values found in {@code ctx} by {@code ***} in every string of {@code value}. */
	private static Object redact(Object value, Map<String, Object> ctx) {
		return value == null ? null : LogMasker.redact(value, LogMasker.credentialValues(ctx));
	}

	/** {@code observed} mirrors the condition tree (a list for AND/OR/NOT); credential fields are never recorded. */
	@SuppressWarnings("unchecked")
	private static Object maskObserved(Map<String, Object> cond, Object observed) {
		if (observed instanceof List<?> obs && cond.get("conditions") instanceof List<?> subs) {
			List<Object> out = new ArrayList<>();
			for (int i = 0; i < obs.size(); i++) {
				out.add(maskObserved((Map<String, Object>) subs.get(i), obs.get(i)));
			}
			return out;
		}
		return LogMasker.isCredential(String.valueOf(cond.get("field"))) ? "***" : observed;
	}

	static Map<String, Object> error(String code, String message, boolean retryable) {
		return Map.of("code", code, "message", message, "retryable", retryable);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> errorInfo(FlowExecution ex) {
		if (ex.errorInfo() == null) {
			return null;
		}
		Map<String, Object> redacted = (Map<String, Object>) redact(ex.errorInfo(), ex.contextData());
		return secrets(redacted);
	}

	/** Persists with optimistic locking and returns the instance carrying the new lock version. */
	private FlowExecution save(FlowExecution ex) {
		ex = new FlowExecution(ex.id(), ex.tenantId(), ex.flowDefinitionId(), ex.flowKey(), ex.flowVersion(),
				ex.snapshot(), ex.correlationId(), ex.status(), ex.inputData(), secrets(ex.contextData()),
				secrets(ex.result()), errorInfo(ex), ex.lockVersion(), ex.startedAt(), ex.completedAt(),
				ex.parentExecutionId(), ex.parentNodeId());
		if (!repository.update(ex)) {
			throw new IllegalStateException("execution " + ex.id() + " was modified concurrently");
		}
		return new FlowExecution(ex.id(), ex.tenantId(), ex.flowDefinitionId(), ex.flowKey(), ex.flowVersion(),
				ex.snapshot(), ex.correlationId(), ex.status(), ex.inputData(), ex.contextData(), ex.result(),
				ex.errorInfo(), ex.lockVersion() + 1, ex.startedAt(), ex.completedAt(), ex.parentExecutionId(),
				ex.parentNodeId());
	}

}
