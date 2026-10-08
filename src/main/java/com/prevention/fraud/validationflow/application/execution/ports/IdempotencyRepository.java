package com.prevention.fraud.validationflow.application.execution.ports;

import java.util.UUID;

/** Port; every per-key operation is scoped by tenant. */
public interface IdempotencyRepository {

	/** Atomically claims (tenant, key); false if already claimed. */
	boolean claimIdempotency(String tenantId, String key, String requestHash, java.time.Duration lease);

	/** The claim as (requestHash, executionId or null while the winner is still running). */
	java.util.Optional<IdempotencyClaim> findIdempotency(String tenantId, String key);

	void completeIdempotency(String tenantId, String key, UUID executionId);

	/** Drops a claim whose execution never started (e.g. rejected input), so the key can be reused. */
	void releaseIdempotency(String tenantId, String key);

	/** Recovery only: deletes claims with no execution whose lease expired, across tenants; returns how many. */
	int deleteExpiredOrphanIdempotency();

	/** Retention only: deletes claims created before the cutoff, across tenants, except live reservations (no execution, lease still valid); returns how many. */
	int deleteIdempotencyCreatedBefore(java.time.Instant cutoff);

	record IdempotencyClaim(String requestHash, UUID executionId) {
	}

}
