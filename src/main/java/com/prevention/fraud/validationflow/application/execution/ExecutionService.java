package com.prevention.fraud.validationflow.application.execution;

import com.prevention.fraud.validationflow.application.execution.ports.ExecutionRepository;
import com.prevention.fraud.validationflow.application.flow.FlowException;
import com.prevention.fraud.validationflow.application.flow.FlowService;
import com.prevention.fraud.validationflow.application.masking.LogMasker;
import com.prevention.fraud.validationflow.application.validator.ValidatorRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.prevention.fraud.validationflow.domain.execution.ConditionEvaluator;
import com.prevention.fraud.validationflow.domain.flow.FlowDefinition;
import com.prevention.fraud.validationflow.domain.execution.FlowExecution;
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

	private final Map<String, NodeHandler> handlers = new HashMap<>();

	public ExecutionService(FlowService flows, ExecutionRepository repository, ValidatorRegistry registry,
			ValidatorRunner runner) {
		this(flows, repository, NodeHandlers.defaults(registry, runner, new SubFlowRunner(flows)));
	}

	/** Handlers are resolved by {@link NodeHandler#type()}; a later handler replaces an earlier one of the same type. */
	public ExecutionService(FlowService flows, ExecutionRepository repository, List<NodeHandler> handlers) {
		this.flows = flows;
		this.repository = repository;
		handlers.forEach(h -> this.handlers.put(h.type(), h));
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
	FlowExecution run(String tenantId, FlowDefinition flow, String correlationId,
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
		Map<String, Object> outputs = new LinkedHashMap<>();
		ctx.put("nodes", outputs);
		try {
			List<String> path = new ArrayList<>(chain);
			path.add(ex.flowKey());
			FlowExecution done = walk(ex, ctx, outputs, path);
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

	private FlowExecution walk(FlowExecution ex, Map<String, Object> ctx, Map<String, Object> outputs,
			List<String> chain) {
		Map<String, Object> nodes = Maps.of(ex.snapshot().get("nodes"));
		String id = (String) ex.snapshot().get("startNodeId");
		while (true) {
			Map<String, Object> node = Maps.of(nodes.get(id));
			String type = (String) node.get("type");
			NodeConfig config = NodeConfig.from(Maps.of(node.get("config")));
			NodeHandler handler = handlers.get(type);
			if (handler == null) {
				return save(ex.fail(ctx, "UNSUPPORTED_NODE_TYPE", type + " nodes are not supported yet (node " + id + ")"));
			}
			FlowExecution done = handler.handle(new NodeStep(ex, id, type, config, ctx, outputs, chain, this));
			if (done != null) {
				return done;
			}
			String next = null;
			for (Map<String, Object> t : Maps.list(node.get("transitions"))) {
				Map<String, Object> cond = t.get("condition") == null ? null : Maps.of(t.get("condition"));
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

	/** Persists the attempt and logs it; payloads only appear in logs masked. */
	void recordNode(FlowExecution ex, String nodeId, String type, int attempt, String status,
			Map<String, Object> output, Map<String, Object> error, Map<String, Object> ctx, Instant started) {
		error = error == null ? null : Maps.of(redact(error, ctx));
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

	private static Map<String, Object> secrets(Map<String, Object> m) {
		return m == null ? null : Maps.of(LogMasker.maskSecrets(m));
	}

	/** Replaces the credential values found in {@code ctx} by {@code ***} in every string of {@code value}. */
	private static Object redact(Object value, Map<String, Object> ctx) {
		return value == null ? null : LogMasker.redact(value, LogMasker.credentialValues(ctx));
	}

	/** {@code observed} mirrors the condition tree (a list for AND/OR/NOT); credential fields are never recorded. */
	private static Object maskObserved(Map<String, Object> cond, Object observed) {
		if (observed instanceof List<?> obs && cond.get("conditions") instanceof List<?> subs) {
			List<Object> out = new ArrayList<>();
			for (int i = 0; i < obs.size(); i++) {
				out.add(maskObserved(Maps.of(subs.get(i)), obs.get(i)));
			}
			return out;
		}
		return LogMasker.isCredential(String.valueOf(cond.get("field"))) ? "***" : observed;
	}

	static Map<String, Object> error(String code, String message, boolean retryable) {
		return Map.of("code", code, "message", message, "retryable", retryable);
	}

	private static Map<String, Object> errorInfo(FlowExecution ex) {
		if (ex.errorInfo() == null) {
			return null;
		}
		Map<String, Object> redacted = Maps.of(redact(ex.errorInfo(), ex.contextData()));
		return secrets(redacted);
	}

	/** Persists with optimistic locking and returns the instance carrying the new lock version. */
	FlowExecution save(FlowExecution ex) {
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
