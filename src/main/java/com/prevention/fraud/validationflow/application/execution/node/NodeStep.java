package com.prevention.fraud.validationflow.application.execution.node;

import com.prevention.fraud.validationflow.application.execution.ExecutionService;
import com.prevention.fraud.validationflow.domain.execution.FlowExecution;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** What a handler sees of the walk: the node, the shared context, and the executor that records and persists. */
public record NodeStep(FlowExecution ex, String nodeId, String type, NodeConfig config, Map<String, Object> ctx,
		Map<String, Object> outputs, List<String> chain, ExecutionService host) {

	public FlowExecution fail(String code, String message) {
		return host.save(ex.fail(ctx, code, message));
	}

	/** Parks the execution in WAITING at this node (history entry PENDING) and returns it; return it from {@link NodeHandler#handle}. */
	public FlowExecution pause(String reason, Instant deadline) {
		return pause(reason, deadline, Map.of());
	}

	/** Same as {@link #pause(String, Instant)}, with {@code details} recorded as the PENDING history entry's output. */
	public FlowExecution pause(String reason, Instant deadline, Map<String, Object> details) {
		host.recorder().recordNode(ex, nodeId, type, 1, "PENDING", details, null, ctx, Instant.now());
		return host.save(ex.waitAt(nodeId, deadline, reason, ctx));
	}

}
