package com.prevention.fraud.validationflow.application.provider;

import java.util.List;
import java.util.Optional;

/** Port; every operation is scoped by tenant and provider. */
public interface ProviderCacheRepository {

	Optional<ProviderCacheEntry> findEntry(String tenantId, String providerKey, String operation, String requestKey);

	/** Inserts, or replaces the entry with the same (tenant, provider, operation, requestKey). */
	void saveEntry(ProviderCacheEntry entry);

	/** Cross-tenant retention sweep: deletes entries whose {@code validUntil} is before the cutoff; returns the count. */
	int deleteEntriesExpiredBefore(java.time.Instant cutoff);

	Optional<ProviderCachePolicy> findPolicy(String tenantId, String providerKey, String operation);

	List<ProviderCachePolicy> findPolicies(String tenantId, String providerKey);

	/** Writes the policy and an audit entry (actor, values) in one transaction. */
	void savePolicy(ProviderCachePolicy policy, String actor);

}
