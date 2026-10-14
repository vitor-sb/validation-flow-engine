package com.prevention.fraud.validationflow.application.execution.recovery;

import java.time.Clock;

import com.prevention.fraud.validationflow.application.execution.WaitCascade;
import java.util.LinkedHashMap;
import java.util.Map;

import com.prevention.fraud.validationflow.application.execution.ports.ExecutionMetrics;
import com.prevention.fraud.validationflow.application.execution.ports.ExecutionRepository;
import com.prevention.fraud.validationflow.domain.execution.FlowExecution;

/** Moves WAITING executions past their deadline to TIMED_OUT. Global across tenants; each row is handled under its own tenant. */
public class WaitExpiryService {

	private final ExecutionRepository repository;

	private final Clock clock;

	private final WaitCascade cascade;

	private final ExecutionMetrics metrics;

	public WaitExpiryService(ExecutionRepository repository, Clock clock, ExecutionMetrics metrics) {
		this.metrics = metrics;
		this.repository = repository;
		this.clock = clock;
		this.cascade = new WaitCascade(repository);
	}

	private static Map<String, Object> waitExpired() {
		Map<String, Object> error = new LinkedHashMap<>();
		error.put("code", "WAIT_EXPIRED");
		error.put("retryable", false);
		error.put("message", "wait deadline expired");
		return error;
	}

	/** Returns how many executions were timed out directly (cascaded ones are counted in the metric only). */
	public int expire() {
		int expired = 0;
		for (FlowExecution ex : repository.findWaitingExpiredBefore(clock.instant())) {
			FlowExecution timedOut = ex.timeOut(waitExpired());
			// optimistic lock: skipped if a resume, cancel or cascade won the race
			if (repository.update(timedOut)) {
				repository.audit(ex.tenantId(), ex.id(), "EXECUTION_WAIT_EXPIRED", ex.waitingNodeId(),
						Map.of("deadline", ex.waitDeadline().toString()));
				metrics.waitExpired();
				cascade.propagate(timedOut, "EXECUTION_WAIT_EXPIRED", (n, up) -> n.timeOut(waitExpired()),
						metrics::waitExpired);
				expired++;
			}
		}
		return expired;
	}

}
