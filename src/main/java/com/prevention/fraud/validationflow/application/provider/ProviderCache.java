package com.prevention.fraud.validationflow.application.provider;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.prevention.fraud.validationflow.application.shared.CanonicalHash;

public class ProviderCache {

	private final ProviderCacheRepository repository;

	public ProviderCache(ProviderCacheRepository repository) {
		this.repository = repository;
	}

	/** Hash of the canonical parameters; the clear values are never stored. */
	public static String requestKey(Map<String, ?> params) {
		return CanonicalHash.sha256(params);
	}

	public Optional<ProviderCacheEntry> find(String tenantId, String providerKey, String operation,
			Map<String, ?> params) {
		return repository.findEntry(tenantId, providerKey, operation, requestKey(params));
	}

	/** Stores the response only when the tenant has a policy for (provider, operation); returns whether it did. */
	public boolean store(String tenantId, String providerKey, String operation, Map<String, ?> params,
			Map<String, Object> response, Instant now) {
		var policy = repository.findPolicy(tenantId, providerKey, operation);
		if (policy.isEmpty() || policy.get().ttlSeconds() == null) {
			return false;
		}
		var p = policy.get();
		repository.saveEntry(new ProviderCacheEntry(UUID.randomUUID(), tenantId, providerKey, operation,
				requestKey(params), response, p.dataCategory(), now, now.plusSeconds(p.ttlSeconds()),
				p.dataCategory()));
		return true;
	}

}
