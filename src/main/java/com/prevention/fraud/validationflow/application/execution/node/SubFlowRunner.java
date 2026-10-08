package com.prevention.fraud.validationflow.application.execution.node;

import com.prevention.fraud.validationflow.application.execution.ExecutionService;
import com.prevention.fraud.validationflow.application.flow.FlowException;
import com.prevention.fraud.validationflow.application.flow.FlowService;
import com.prevention.fraud.validationflow.domain.flow.ConditionEvaluator;
import com.prevention.fraud.validationflow.domain.execution.ExecutionStatus;
import com.prevention.fraud.validationflow.domain.execution.FlowExecution;
import com.prevention.fraud.validationflow.domain.flow.FlowDefinition;
import com.prevention.fraud.validationflow.domain.flow.GraphValidator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Runs the child flow of a SUB_FLOW node: cycle/depth checks, input mapping and output mapping. */
public class SubFlowRunner {

	/** Runs a flow as a child; implemented by {@link ExecutionService}. */
	@FunctionalInterface
	public interface ChildRun {

		FlowExecution run(String tenantId, FlowDefinition flow, String correlationId, Map<String, Object> input,
				UUID parentId, String parentNodeId, List<String> chain, Map<String, Object> ctx);

	}

	/** Either a failure ({@code code} set, with {@code details}) or the mapped {@code output}. */
	public record Outcome(String code, String message, Map<String, Object> details, Map<String, Object> output) {
	}

	private final FlowService flows;

	public SubFlowRunner(FlowService flows) {
		this.flows = flows;
	}

	/**
	 * Runs the child flow (active version now) with only the mapped input and maps back only the mapped output. Depth:
	 * the child runs at level {@code chain.size()} (root = 0) and may not exceed the node's maxDepth (default
	 * {@link GraphValidator#MAX_SUB_FLOW_DEPTH}).
	 */
	public Outcome run(FlowExecution ex, String nodeId, NodeConfig config, Map<String, Object> ctx,
			List<String> chain, ChildRun childRun) {
		String childKey = config.flowKey();
		int limit = config.maxDepth() != null ? config.maxDepth() : GraphValidator.MAX_SUB_FLOW_DEPTH;
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
			config.inputMapping().forEach((name, path) -> {
				Object v = ConditionEvaluator.lookup(ctx, jsonPath(path));
				if (v != null) {
					input.put(name, v);
				}
			});
			try {
				FlowDefinition flow = flows.resolveActive(ex.tenantId(), childKey, null, null);
				child = childRun.run(ex.tenantId(), flow, ex.correlationId(), input, ex.id(), nodeId, chain, childCtx);
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
			return new Outcome(code, message, details, null);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		config.outputMapping().forEach((name, path) -> {
			Object v = ConditionEvaluator.lookup(childCtx, jsonPath(path));
			if (v != null) {
				out.put(name, v);
			}
		});
		out.put("success", true);
		out.put("childExecutionId", child.id().toString());
		return new Outcome(null, null, details, out);
	}

	/** Only plain dotted JSONPath ({@code $.a.b}) is supported; {@code $} alone is the whole context. */
	private static String jsonPath(String path) {
		return path.startsWith("$.") ? path.substring(2) : path.substring(1);
	}

}
