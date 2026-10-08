package com.prevention.fraud.validationflow.application.execution;

import com.prevention.fraud.validationflow.domain.execution.FlowExecution;
import java.util.List;
import java.util.Map;

/** What a handler sees of the walk: the node, the shared context, and the executor that records and persists. */
public record NodeStep(FlowExecution ex, String nodeId, String type, NodeConfig config, Map<String, Object> ctx,
		Map<String, Object> outputs, List<String> chain, ExecutionService host) {

	public FlowExecution fail(String code, String message) {
		return host.save(ex.fail(ctx, code, message));
	}

}
