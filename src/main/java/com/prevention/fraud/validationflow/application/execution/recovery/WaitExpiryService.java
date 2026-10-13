package com.prevention.fraud.validationflow.application.execution.recovery;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

import com.prevention.fraud.validationflow.application.execution.ports.ExecutionMetrics;
import com.prevention.fraud.validationflow.application.execution.ports.ExecutionRepository;
import com.prevention.fraud.validationflow.domain.execution.FlowExecution;

/** Moves WAITING executions past their deadline to TIMED_OUT. Global across tenants; each row is handled under its own tenant. */
public class WaitExpiryService {

	private final ExecutionRepository repository;

	private final Clock clock;

	private final ExecutionMetrics metrics;

	public WaitExpiryService(ExecutionRepository repository, Clock clock, ExecutionMetrics metrics) {
		this.metrics = metrics;
		this.repository = repository;
		this.clock = clock;
	}

	/** Returns how many executions were timed out. */
	public int expire() {
		int expired = 0;
		for (FlowExecution ex : repository.findWaitingExpiredBefore(clock.instant())) {
			Map<String, Object> error = new LinkedHashMap<>();
			error.put("code", "WAIT_EXPIRED");
			error.put("retryable", false);
			error.put("message", "wait deadline expired");
			// optimistic lock: skipped if a resume or cancel won the race
			if (repository.update(ex.timeOut(error))) {
				repository.audit(ex.tenantId(), ex.id(), "EXECUTION_WAIT_EXPIRED", ex.waitingNodeId(),
						Map.of("deadline", ex.waitDeadline().toString()));
				metrics.waitExpired();
				expired++;
			}
		}
		return expired;
	}

}
