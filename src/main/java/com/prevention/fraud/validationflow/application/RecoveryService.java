package com.prevention.fraud.validationflow.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import com.prevention.fraud.validationflow.domain.FlowExecution;

/** Finishes executions abandoned in RUNNING (e.g. JVM crash). Global across tenants; each row is handled under its own tenant. */
public class RecoveryService {

	private final ExecutionRepository repository;

	private final Duration staleAfter;

	public RecoveryService(ExecutionRepository repository, Duration staleAfter) {
		this.repository = repository;
		this.staleAfter = staleAfter;
	}

	/** Returns how many executions were marked FAILED. */
	public int recover() {
		int failed = 0;
		for (FlowExecution ex : repository.findRunningStartedBefore(Instant.now().minus(staleAfter))) {
			FlowExecution f = ex.fail(ex.contextData(), "EXECUTION_ABANDONED", "execution abandoned while RUNNING");
			// optimistic lock: skipped if the execution finished (or changed) since it was read
			if (repository.update(f)) {
				repository.audit(ex.tenantId(), ex.id(), "EXECUTION_ABANDONED", null,
						Map.of("staleAfter", staleAfter.toString()));
				failed++;
			}
		}
		repository.deleteExpiredOrphanIdempotency();
		return failed;
	}

}
