package com.prevention.fraud.validationflow.application.execution.node;

import com.prevention.fraud.validationflow.domain.execution.FlowExecution;

/** Executes one node type. The walk loop resolves the handler by {@link #type()}; add a type by adding a handler. */
public interface NodeHandler {

	public String type();

	/** Returns the terminal execution (END or failure), or null to continue with the node's transitions. */
	public FlowExecution handle(NodeStep step);

}
