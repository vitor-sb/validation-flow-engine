package com.prevention.fraud.validationflow.application.execution;

import com.prevention.fraud.validationflow.application.execution.history.ExecutionRecorder;
import com.prevention.fraud.validationflow.application.execution.node.Maps;
import com.prevention.fraud.validationflow.application.execution.node.NodeConfig;
import com.prevention.fraud.validationflow.application.execution.node.NodeHandler;
import com.prevention.fraud.validationflow.application.execution.node.NodeHandlers;
import com.prevention.fraud.validationflow.application.execution.node.NodeStep;
import com.prevention.fraud.validationflow.application.execution.node.SubFlowRunner;
import com.prevention.fraud.validationflow.application.execution.node.ValidatorRunner;

import com.prevention.fraud.validationflow.application.execution.ports.ExecutionMetrics;
import com.prevention.fraud.validationflow.application.execution.ports.ExecutionRepository;
import com.prevention.fraud.validationflow.application.flow.FlowException;
import com.prevention.fraud.validationflow.application.flow.FlowService;
import com.prevention.fraud.validationflow.application.masking.LogMasker;
import com.prevention.fraud.validationflow.application.validator.ValidatorRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.prevention.fraud.validationflow.domain.flow.ConditionEvaluator;
import com.prevention.fraud.validationflow.domain.flow.FlowDefinition;
import com.prevention.fraud.validationflow.domain.execution.ExecutionStatus;
import com.prevention.fraud.validationflow.domain.execution.FlowExecution;
import com.prevention.fraud.validationflow.domain.execution.NodeExecution;



public class ExecutionService {

	/** Persisted instead of the message of unexpected exceptions, which may carry input data. */
	public static final String UNEXPECTED = "unexpected error";

	private final FlowService flows;

	private final ExecutionRepository repository;

	private final ExecutionRecorder recorder;

	private final WaitCascade cascade;

	private final Map<String, NodeHandler> handlers = new HashMap<>();

	public ExecutionService(FlowService flows, ExecutionRepository repository, ValidatorRegistry registry,
			ValidatorRunner runner) {
		this(flows, repository, NodeHandlers.defaults(registry, runner, new SubFlowRunner(flows)), runner.metrics());
	}

	/** Handlers are resolved by {@link NodeHandler#type()}; a later handler replaces an earlier one of the same type. */
	public ExecutionService(FlowService flows, ExecutionRepository repository, List<NodeHandler> handlers,
			ExecutionMetrics metrics) {
		this.flows = flows;
		this.repository = repository;
		this.recorder = new ExecutionRecorder(repository, metrics);
		this.cascade = new WaitCascade(repository);
		handlers.forEach(h -> this.handlers.put(h.type(), h));
		metrics.waitingGauge(repository::countWaiting);
	}

	/** Synchronous: resolves the active flow, validates the input contract, walks the snapshot graph, returns the final state. */
	public FlowExecution execute(String tenantId, String flowKey, String userType, String context,
			Map<String, Object> inputData, String correlationId) {
		FlowDefinition flow = flows.resolveActive(tenantId, flowKey, userType, context);
		var started = Instant.now();
		FlowExecution done = run(tenantId, flow, correlationId, inputData, null, null, List.of(), new HashMap<>());
		if (done.status() != ExecutionStatus.WAITING) {
			recorder.executionFinished(done.status().name(), Duration.between(started, Instant.now()));
		}
		return done;
	}

	/**
	 * Runs one flow; {@code chain} holds the flowKeys of the ancestors (empty for a root run) and {@code ctx} is filled
	 * with the execution context so a parent can read the child's unmasked outputs.
	 */
	public FlowExecution run(String tenantId, FlowDefinition flow, String correlationId,
			Map<String, Object> inputData, UUID parentId, String parentNodeId, List<String> chain,
			Map<String, Object> ctx) {
		List<String> missing = flow.inputContract().stream()
				.filter(f -> f.required() && inputData.get(f.name()) == null).map(f -> f.name()).toList();
		if (!missing.isEmpty()) {
			throw FlowException.invalidInput("missing required input field(s): " + String.join(", ", missing));
		}
		FlowExecution ex = FlowExecution.pending(tenantId, flow, correlationId, inputData, parentId, parentNodeId);
		repository.insert(new FlowExecution(ex.id(), ex.tenantId(), ex.flowDefinitionId(), ex.flowKey(), ex.flowVersion(),
				ex.snapshot(), ex.correlationId(), ex.status(), LogMasker.secrets(inputData), ex.contextData(), ex.result(),
				ex.errorInfo(), ex.lockVersion(), ex.startedAt(), ex.completedAt(), ex.parentExecutionId(),
				ex.parentNodeId()));
		ex = save(ex.start());
		var outerMdc = recorder.enterMdc(ex, correlationId);
		// fresh map per execution: nothing is shared between runs
		ctx.put("inputData", inputData);
		Map<String, Object> outputs = new LinkedHashMap<>();
		ctx.put("nodes", outputs);
		try {
			List<String> path = new ArrayList<>(chain);
			path.add(ex.flowKey());
			FlowExecution done = walk(ex, ctx, outputs, path, (String) ex.snapshot().get("startNodeId"));
			recorder.finished(done);
			return done;
		}
		catch (RuntimeException e) {
			recorder.failed(e, ctx);
			return save(ex.fail(ctx, "EXECUTION_ERROR", UNEXPECTED, false, Map.of("exception", e.getClass().getName())));
		}
		finally {
			recorder.restoreMdc(outerMdc);
		}
	}

	private FlowExecution walk(FlowExecution ex, Map<String, Object> ctx, Map<String, Object> outputs,
			List<String> chain, String id) {
		Map<String, Object> nodes = Maps.of(ex.snapshot().get("nodes"));
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
			String next = nextNode(ex, id, node, ctx);
			if (next == null) {
				return save(ex.fail(ctx, "NO_MATCHING_TRANSITION", "no transition matched at node " + id));
			}
			id = next;
		}
	}

	/** First transition of the node whose condition holds (no condition = default branch); null if none matches. */
	private String nextNode(FlowExecution ex, String id, Map<String, Object> node, Map<String, Object> ctx) {
		for (Map<String, Object> t : Maps.list(node.get("transitions"))) {
			Map<String, Object> cond = t.get("condition") == null ? null : Maps.of(t.get("condition"));
			var ev = cond == null ? null : ConditionEvaluator.evaluate(cond, ctx);
			recorder.auditTransition(ex, id, t.get("to"), ev == null || ev.result(),
					ev == null ? null : LogMasker.maskObserved(cond, ev.observed()));
			if (ev == null || ev.result()) {
				return (String) t.get("to");
			}
		}
		return null;
	}

	/**
	 * Resumes a WAITING execution of the tenant from its waiting node, over the original snapshot, following the
	 * transition labelled with {@code decision}. 409 if it is not WAITING or another resume won the race.
	 */
	public FlowExecution resume(String tenantId, UUID id, String decision, String note, Map<String, Object> evidence,
			String actor) {
		FlowExecution waiting = get(tenantId, id);
		if (waiting.status() != ExecutionStatus.WAITING) {
			throw FlowException.conflict("execution is not waiting (status " + waiting.status() + ")");
		}
		String nodeId = waiting.waitingNodeId();
		Map<String, Object> node = Maps.of(Maps.of(waiting.snapshot().get("nodes")).get(nodeId));
		if ("SUB_FLOW".equals(node.get("type"))) {
			throw FlowException.conflict("execution is waiting on sub-flow execution " + pendingChild(waiting)
					+ "; resume that one instead");
		}
		String next = Maps.list(node.get("transitions")).stream().filter(t -> decision.equals(t.get("label")))
				.map(t -> (String) t.get("to")).findFirst().orElse(null);
		Map<String, Object> decided = new LinkedHashMap<>();
		decided.put("decision", decision);
		decided.put("decidedBy", actor);
		if (note != null) {
			decided.put("note", note);
		}
		if (evidence != null) {
			decided.put("evidence", evidence);
		}
		return resumeAt(waiting, node, decided, decided, next, false);
	}

	/**
	 * Resumes the execution waiting on an EXTERNAL_CALL node with the provider's normalized {@code data}: the node output
	 * is the data plus {@code success} (like VALIDATION) and the transitions are evaluated normally. 409 if the
	 * execution is not waiting at {@code nodeId}.
	 */
	public FlowExecution resumeWithProviderResult(String tenantId, UUID id, String nodeId, UUID callId,
			Map<String, Object> data, boolean success) {
		FlowExecution waiting = get(tenantId, id);
		if (waiting.status() != ExecutionStatus.WAITING || !nodeId.equals(waiting.waitingNodeId())) {
			throw FlowException.conflict("execution is not waiting for this call (status " + waiting.status() + ")");
		}
		Map<String, Object> node = Maps.of(Maps.of(waiting.snapshot().get("nodes")).get(nodeId));
		Map<String, Object> out = new LinkedHashMap<>(data);
		out.put("success", success);
		return resumeAt(waiting, node, out, Map.of("callId", callId.toString(), "source", "PROVIDER"), null, true);
	}

	/** Shared continuation: stores {@code output} for the waiting node, then follows {@code next} or, if {@code byTransitions}, the node's own transitions. */
	private FlowExecution resumeAt(FlowExecution waiting, Map<String, Object> node, Map<String, Object> output,
			Map<String, Object> auditDetails, String next, boolean byTransitions) {
		String nodeId = waiting.waitingNodeId();
		String tenantId = waiting.tenantId();
		FlowExecution ex;
		try {
			ex = save(waiting.resume());
		}
		catch (IllegalStateException e) {
			throw FlowException.conflict("execution was modified concurrently");
		}
		recorder.metrics().resumed();
		Map<String, Object> ctx = new HashMap<>(ex.contextData());
		Map<String, Object> outputs = new LinkedHashMap<>(Maps.of(ctx.get("nodes")));
		ctx.put("nodes", outputs);
		var outerMdc = recorder.enterMdc(ex, ex.correlationId());
		try {
			outputs.put(nodeId, output);
			recorder.recordNode(ex, nodeId, (String) node.get("type"), 2, "COMPLETED", output, null, ctx, Instant.now());
			repository.audit(tenantId, ex.id(), "EXECUTION_RESUMED", nodeId, auditDetails);
			String target = byTransitions ? nextNode(ex, nodeId, node, ctx) : next;
			FlowExecution done = target == null
					? save(ex.fail(ctx, "NO_MATCHING_TRANSITION", byTransitions
							? "no transition matched at node " + nodeId : "no transition for decision at node " + nodeId))
					: walk(ex, ctx, outputs, List.of(ex.flowKey()), target);
			recorder.finished(done);
			continueParent(done, ctx);
			return done;
		}
		catch (RuntimeException e) {
			recorder.failed(e, ctx);
			return save(ex.fail(ctx, "EXECUTION_ERROR", UNEXPECTED, false, Map.of("exception", e.getClass().getName())));
		}
		finally {
			recorder.restoreMdc(outerMdc);
		}
	}

	private String pendingChild(FlowExecution parent) {
		return repository.findNodes(parent.tenantId(), parent.id()).stream()
				.filter(n -> n.nodeId().equals(parent.waitingNodeId()) && "PENDING".equals(n.status()))
				.map(n -> String.valueOf(n.outputData().get("childExecutionId"))).findFirst().orElse("?");
	}

	/**
	 * After a child that was paused in a SUB_FLOW node ends, continues the parent from that node: output mapping and
	 * normal transitions on COMPLETED, SUB_FLOW_FAILED on any other final state, untouched while the child waits again.
	 * Recurses up the chain; a lost optimistic lock on the parent means another resume owns it (409).
	 */
	private void continueParent(FlowExecution child, Map<String, Object> childCtx) {
		if (child.parentExecutionId() == null || child.status() == ExecutionStatus.WAITING) {
			return;
		}
		FlowExecution parent = get(child.tenantId(), child.parentExecutionId());
		if (parent.status() != ExecutionStatus.WAITING || !child.parentNodeId().equals(parent.waitingNodeId())) {
			return;
		}
		String nodeId = parent.waitingNodeId();
		Map<String, Object> node = Maps.of(Maps.of(parent.snapshot().get("nodes")).get(nodeId));
		FlowExecution ex;
		try {
			ex = save(parent.resume());
		}
		catch (IllegalStateException e) {
			throw FlowException.conflict("execution was modified concurrently");
		}
		recorder.metrics().resumed();
		Map<String, Object> ctx = new HashMap<>(ex.contextData());
		Map<String, Object> outputs = new LinkedHashMap<>(Maps.of(ctx.get("nodes")));
		ctx.put("nodes", outputs);
		var outerMdc = recorder.enterMdc(ex, ex.correlationId());
		FlowExecution done;
		try {
			Instant started = Instant.now();
			if (child.status() == ExecutionStatus.COMPLETED) {
				Map<String, Object> out = new LinkedHashMap<>();
				NodeConfig.from(Maps.of(node.get("config"))).outputMapping().forEach((name, path) -> {
					Object v = ConditionEvaluator.lookup(childCtx, path.startsWith("$.") ? path.substring(2) : path.substring(1));
					if (v != null) {
						out.put(name, v);
					}
				});
				out.put("success", true);
				out.put("childExecutionId", child.id().toString());
				outputs.put(nodeId, out);
				recorder.recordNode(ex, nodeId, "SUB_FLOW", 2, "COMPLETED", out, null, ctx, started);
				String next = nextNode(ex, nodeId, node, ctx);
				done = next == null ? save(ex.fail(ctx, "NO_MATCHING_TRANSITION", "no transition matched at node " + nodeId))
						: walk(ex, ctx, outputs, List.of(ex.flowKey()), next);
			}
			else {
				String message = "sub-flow " + child.flowKey() + " ended " + child.status();
				Map<String, Object> details = new LinkedHashMap<>(Map.of("nodeId", nodeId, "childExecutionId",
						child.id().toString(), "childError", child.errorInfo() == null ? Map.of() : child.errorInfo()));
				recorder.recordNode(ex, nodeId, "SUB_FLOW", 2, "FAILED", Map.of(),
						error("SUB_FLOW_FAILED", message, false), ctx, started);
				done = save(ex.fail(ctx, "SUB_FLOW_FAILED", message, false, details));
			}
			recorder.finished(done);
		}
		catch (RuntimeException e) {
			recorder.failed(e, ctx);
			done = save(ex.fail(ctx, "EXECUTION_ERROR", UNEXPECTED, false, Map.of("exception", e.getClass().getName())));
		}
		finally {
			recorder.restoreMdc(outerMdc);
		}
		continueParent(done, ctx);
	}

	/** Cancels a WAITING execution of the tenant. RUNNING and final states are 409 (no cooperative cancellation). */
	public FlowExecution cancel(String tenantId, UUID id, String reason, String actor) {
		FlowExecution ex = get(tenantId, id);
		if (ex.status() != ExecutionStatus.WAITING) {
			throw FlowException.conflict(ex.status() == ExecutionStatus.RUNNING
					? "execution is running; only WAITING executions can be cancelled"
					: "execution already finished (status " + ex.status() + ")");
		}
		String nodeId = ex.waitingNodeId();
		FlowExecution done;
		try {
			done = save(ex.cancel(reason));
		}
		catch (IllegalStateException e) {
			throw FlowException.conflict("execution was modified concurrently");
		}
		recorder.metrics().cancelled();
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("cancelledBy", actor);
		if (reason != null) {
			details.put("reason", reason);
		}
		repository.audit(tenantId, id, "EXECUTION_CANCELLED", nodeId, details);
		cascade.propagate(done, "EXECUTION_CANCELLED",
				(n, up) -> n.cancel(up ? "CHILD_CANCELLED" : "PARENT_CANCELLED"), recorder.metrics()::cancelled);
		return done;
	}

	public ExecutionRecorder recorder() {
		return recorder;
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

	public static Map<String, Object> error(String code, String message, boolean retryable) {
		return Map.of("code", code, "message", message, "retryable", retryable);
	}

	private static Map<String, Object> errorInfo(FlowExecution ex) {
		if (ex.errorInfo() == null) {
			return null;
		}
		Map<String, Object> redacted = Maps.of(LogMasker.redact(ex.errorInfo(), ex.contextData()));
		return LogMasker.secrets(redacted);
	}

	/** Persists with optimistic locking and returns the instance carrying the new lock version. */
	public FlowExecution save(FlowExecution ex) {
		ex = new FlowExecution(ex.id(), ex.tenantId(), ex.flowDefinitionId(), ex.flowKey(), ex.flowVersion(),
				ex.snapshot(), ex.correlationId(), ex.status(), ex.inputData(), LogMasker.secrets(ex.contextData()),
				LogMasker.secrets(ex.result()), errorInfo(ex), ex.lockVersion(), ex.startedAt(), ex.completedAt(),
				ex.parentExecutionId(), ex.parentNodeId(), ex.waitingNodeId(), ex.waitDeadline(),
				ex.waitReason());
		if (!repository.update(ex)) {
			throw new IllegalStateException("execution " + ex.id() + " was modified concurrently");
		}
		return new FlowExecution(ex.id(), ex.tenantId(), ex.flowDefinitionId(), ex.flowKey(), ex.flowVersion(),
				ex.snapshot(), ex.correlationId(), ex.status(), ex.inputData(), ex.contextData(), ex.result(),
				ex.errorInfo(), ex.lockVersion() + 1, ex.startedAt(), ex.completedAt(), ex.parentExecutionId(),
				ex.parentNodeId(), ex.waitingNodeId(), ex.waitDeadline(), ex.waitReason());
	}

}
