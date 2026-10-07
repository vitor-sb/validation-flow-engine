package com.prevention.fraud.validationflow.application;

import java.time.Duration;
import java.time.Instant;

/** Purges old idempotency keys; a replay after the retention window runs the flow again. Global across tenants. */
public class IdempotencyRetentionService {

	private final ExecutionRepository repository;

	private final Duration retention;

	public IdempotencyRetentionService(ExecutionRepository repository, Duration retention) {
		this.repository = repository;
		this.retention = retention;
	}

	/** Returns how many keys were deleted. */
	public int purge() {
		return repository.deleteIdempotencyCreatedBefore(Instant.now().minus(retention));
	}

}
