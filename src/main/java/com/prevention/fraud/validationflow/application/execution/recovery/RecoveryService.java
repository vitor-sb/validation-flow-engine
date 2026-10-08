package com.prevention.fraud.validationflow.application.execution.recovery;

import com.prevention.fraud.validationflow.application.execution.ports.ExecutionRepository;
import com.prevention.fraud.validationflow.application.execution.ports.IdempotencyRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import com.prevention.fraud.validationflow.domain.execution.FlowExecution;

/** Finishes executions abandoned in RUNNING (e.g. JVM crash). Global across tenants; each row is handled under its own tenant. */
public class RecoveryService {

	private final ExecutionRepository repository;

	private final IdempotencyRepository idempotency;

	private final Duration staleAfter;

	public RecoveryService(ExecutionRepository repository, IdempotencyRepository idempotency, Duration staleAfter) {
		this.repository = repository;
		this.idempotency = idempotency;
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
		idempotency.deleteExpiredOrphanIdempotency();
		return failed;
	}

}
