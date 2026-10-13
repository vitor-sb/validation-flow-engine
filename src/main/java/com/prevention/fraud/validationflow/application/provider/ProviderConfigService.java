package com.prevention.fraud.validationflow.application.provider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Resolves a provider's config with a short per-process cache; a missing row or {@code enabled=false} counts as disabled. */
public class ProviderConfigService {

	private record Entry(Optional<ProviderConfig> config, Instant loadedAt) {
	}

	private final ProviderConfigRepository repository;
	private final Duration ttl;
	private final Clock clock;
	private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();

	public ProviderConfigService(ProviderConfigRepository repository, Duration ttl, Clock clock) {
		this.repository = repository;
		this.ttl = ttl;
		this.clock = clock;
	}

	/** Empty when the provider is disabled for the tenant. */
	public Optional<ProviderConfig> resolve(String tenantId, String providerKey) {
		var now = clock.instant();
		var entry = cache.compute(key(tenantId, providerKey), (k, old) ->
				old != null && old.loadedAt().plus(ttl).isAfter(now) ? old
						: new Entry(repository.find(tenantId, providerKey).filter(ProviderConfig::enabled), now));
		return entry.config();
	}

	/** Drops the cached entry so the next resolve reads the database. */
	public void invalidate(String tenantId, String providerKey) {
		cache.remove(key(tenantId, providerKey));
	}

	private static String key(String tenantId, String providerKey) {
		return tenantId + '\0' + providerKey;
	}

}
