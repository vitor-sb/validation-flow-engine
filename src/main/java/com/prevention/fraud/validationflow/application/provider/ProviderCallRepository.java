package com.prevention.fraud.validationflow.application.provider;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port; every operation is scoped by tenant. */
public interface ProviderCallRepository {

	/** Creates or replaces the row. */
	void save(ProviderCall call);

	/** Callback lookup: the caller has no tenant yet, the call's own token authenticates it. */
	Optional<ProviderCall> findById(UUID id);

	Optional<ProviderCall> find(String tenantId, UUID id);

	/**
	 * Polling: returns up to {@code limit} PENDING POLL calls due at {@code now} (any tenant) and pushes their
	 * {@code nextPollAt} to {@code leaseUntil} in the same transaction, using FOR UPDATE SKIP LOCKED, so concurrent
	 * instances never get the same call and a crashed instance's calls come back after the lease.
	 */
	List<ProviderCall> claimDuePolls(Instant now, Instant leaseUntil, int limit);

	/**
	 * Dead letter: moves FAILED calls, and PENDING calls whose execution is no longer WAITING (expired, cancelled, ended),
	 * to DEAD with a reason, clearing {@code nextPollAt}. Returns how many rows changed.
	 */
	int sweepDead(Instant now);

	List<ProviderCall> listByStatus(String tenantId, ProviderCall.Status status, int page, int size);

	long countByStatus(String tenantId, ProviderCall.Status status);

	/** All tenants; feeds the operational gauges. */
	long countByStatus(ProviderCall.Status status);

}
