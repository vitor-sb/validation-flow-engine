package com.prevention.fraud.validationflow.application.provider;

import java.util.Optional;

/** Port; every operation is scoped by tenant and provider. */
public interface ProviderCacheRepository {

	Optional<ProviderCacheEntry> findEntry(String tenantId, String providerKey, String operation, String requestKey);

	/** Inserts, or replaces the entry with the same (tenant, provider, operation, requestKey). */
	void saveEntry(ProviderCacheEntry entry);

	Optional<ProviderCachePolicy> findPolicy(String tenantId, String providerKey, String operation);

	void savePolicy(ProviderCachePolicy policy);

}
