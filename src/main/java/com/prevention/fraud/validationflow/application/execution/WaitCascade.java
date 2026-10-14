package com.prevention.fraud.validationflow.application.execution;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

import com.prevention.fraud.validationflow.application.execution.ports.ExecutionRepository;
import com.prevention.fraud.validationflow.domain.execution.ExecutionStatus;
import com.prevention.fraud.validationflow.domain.execution.FlowExecution;

/**
 * After a WAITING execution ends (cancelled, expired), ends the WAITING parent and children linked to it, transitively,
 * so a sub-flow pair is never left half-waiting. Only WAITING neighbours are touched; the optimistic lock decides races.
 */
public class WaitCascade {

	private final ExecutionRepository repository;

	public WaitCascade(ExecutionRepository repository) {
		this.repository = repository;
	}

	/** {@code end} receives the neighbour and whether it is the parent (true) or a child (false). */
	public void propagate(FlowExecution origin, String event, BiFunction<FlowExecution, Boolean, FlowExecution> end,
			Runnable count) {
		visit(origin, event, end, count, new HashSet<>(Set.of(origin.id())));
	}

	private void visit(FlowExecution origin, String event, BiFunction<FlowExecution, Boolean, FlowExecution> end,
			Runnable count, Set<UUID> seen) {
		if (origin.parentExecutionId() != null) {
			repository.findById(origin.tenantId(), origin.parentExecutionId())
					.filter(p -> origin.parentNodeId().equals(p.waitingNodeId()))
					.ifPresent(p -> endOne(p, true, origin, event, end, count, seen));
		}
		for (FlowExecution child : repository.findWaitingChildren(origin.tenantId(), origin.id())) {
			endOne(child, false, origin, event, end, count, seen);
		}
	}

	private void endOne(FlowExecution n, boolean parent, FlowExecution origin, String event,
			BiFunction<FlowExecution, Boolean, FlowExecution> end, Runnable count, Set<UUID> seen) {
		if (n.status() != ExecutionStatus.WAITING || !seen.add(n.id())) {
			return;
		}
		FlowExecution ended = end.apply(n, parent);
		if (!repository.update(ended)) {
			return;
		}
		repository.audit(n.tenantId(), n.id(), event, n.waitingNodeId(), Map.of("cause", origin.id().toString()));
		count.run();
		visit(ended, event, end, count, seen);
	}

}
