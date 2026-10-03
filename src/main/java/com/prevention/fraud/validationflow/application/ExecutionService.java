package com.prevention.fraud.validationflow.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.prevention.fraud.validationflow.domain.ConditionEvaluator;
import com.prevention.fraud.validationflow.domain.FlowDefinition;
import com.prevention.fraud.validationflow.domain.FlowExecution;

public class ExecutionService {

	private final FlowService flows;

	private final ExecutionRepository repository;

	private final ValidatorRegistry registry;

	public ExecutionService(FlowService flows, ExecutionRepository repository, ValidatorRegistry registry) {
		this.flows = flows;
		this.repository = repository;
		this.registry = registry;
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
		// fresh map per execution: nothing is shared between runs
		Map<String, Object> ctx = new HashMap<>();
		ctx.put("inputData", inputData);
		ctx.put("nodes", new LinkedHashMap<String, Object>());
		try {
			return walk(ex, ctx);
		}
		catch (RuntimeException e) {
			return save(ex.fail(ctx, "EXECUTION_ERROR", String.valueOf(e.getMessage())));
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
				case "START", "DECISION" -> repository.recordNode(ex.tenantId(), ex.id(), id, type, true, Map.of());
				case "VALIDATION" -> {
					ValidatorStrategy v = registry.find((String) config.get("validatorType")).orElse(null);
					if (v == null) {
						return save(ex.fail(ctx, "VALIDATOR_NOT_FOUND", "validator not registered at node " + id));
					}
					var r = v.execute(new ValidatorStrategy.ValidationInput(Map.copyOf(ctx), config));
					Map<String, Object> out = new LinkedHashMap<>(r.output() == null ? Map.of() : r.output());
					out.put("success", r.success());
					((Map<String, Object>) ctx.get("nodes")).put(id, out);
					repository.recordNode(ex.tenantId(), ex.id(), id, type, r.success(), out);
				}
				case "END" -> {
					repository.recordNode(ex.tenantId(), ex.id(), id, type, true, Map.of());
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
