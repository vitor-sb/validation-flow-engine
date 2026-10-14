package com.prevention.fraud.validationflow.application.provider;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.prevention.fraud.validationflow.application.shared.CanonicalHash;

public class ProviderCache {

	/** Nodes have no operation concept yet: every cached call is the provider's single "default" operation. */
	public static final String OPERATION = "default";

	public static final String HIT = "HIT", MISS = "MISS", EXPIRED = "EXPIRED", BYPASS = "BYPASS", DISABLED = "DISABLED",
			STALE_FALLBACK = "STALE_FALLBACK";

	/** Outcome of a lookup: {@code entry} is set only for HIT and EXPIRED. */
	public record Lookup(String status, ProviderCacheEntry entry) {

		public boolean hit() {
			return HIT.equals(status);
		}

		/** Copy of {@code out} with {@code cacheStatus}, {@code cacheAgeSeconds} and (when one applied) {@code cachePolicy}. */
		public Map<String, Object> tag(Map<String, Object> out, String policy, Instant now) {
			Map<String, Object> tagged = new LinkedHashMap<>(out);
			tagged.put("cacheStatus", status);
			tagged.put("cacheAgeSeconds", entry == null ? 0 : Math.max(0, Duration.between(entry.fetchedAt(), now).toSeconds()));
			String applied = entry != null ? entry.policyApplied() : policy;
			if (applied != null) {
				tagged.put("cachePolicy", applied);
			}
			return tagged;
		}

	}

	private final ProviderCacheRepository repository;
	private final CachePolicyService policies;
	private final Duration negativeTtl;

	public ProviderCache(ProviderCacheRepository repository, CachePolicyService policies, Duration negativeTtl) {
		this.repository = repository;
		this.policies = policies;
		this.negativeTtl = negativeTtl;
	}

	/** Hash of the canonical parameters; the clear values are never stored. */
	public static String requestKey(Map<String, ?> params) {
		return CanonicalHash.sha256(params);
	}

	public Optional<ProviderCacheEntry> find(String tenantId, String providerKey, String operation,
			Map<String, ?> params) {
		return repository.findEntry(tenantId, providerKey, operation, requestKey(params));
	}

	/** BYPASS skips the read (the fresh answer still refreshes the entry); no policy means DISABLED. */
	public Lookup lookup(String tenantId, String providerKey, String operation, Map<String, ?> params, boolean bypass,
			Instant now) {
		if (bypass) {
			return new Lookup(BYPASS, null);
		}
		if (policies.resolve(tenantId, providerKey, operation).isEmpty()) {
			return new Lookup(DISABLED, null);
		}
		var entry = find(tenantId, providerKey, operation, params);
		if (entry.isEmpty()) {
			return new Lookup(MISS, null);
		}
		return new Lookup(entry.get().validUntil().isAfter(now) ? HIT : EXPIRED, entry.get());
	}

	/**
	 * The stored answer to serve, flagged STALE_FALLBACK, when the provider failed for good: only if a policy allows
	 * {@code fallbackOnErrorAllowed} and an entry (valid or expired) exists. The caller must record the failure reason.
	 */
	public Optional<Lookup> staleFallback(String tenantId, String providerKey, String operation, Map<String, ?> params) {
		if (!policies.resolve(tenantId, providerKey, operation).filter(ProviderCachePolicy::fallbackOnErrorAllowed).isPresent()) {
			return Optional.empty();
		}
		return find(tenantId, providerKey, operation, params).map(e -> new Lookup(STALE_FALLBACK, e));
	}

	/**
	 * Stores the response only when a cacheable policy applies to (tenant, provider, operation); returns the applied
	 * policy label, or null when nothing was stored. A {@code found:false} answer lives at most {@code negativeTtl}.
	 */
	public String store(String tenantId, String providerKey, String operation, Map<String, ?> params,
			Map<String, Object> response, Instant now) {
		var policy = policies.resolve(tenantId, providerKey, operation);
		if (policy.isEmpty()) {
			return null;
		}
		var p = policy.get();
		long ttl = p.ttlSeconds();
		if (Boolean.FALSE.equals(response.get("found"))) {
			ttl = Math.min(ttl, negativeTtl.toSeconds());
		}
		repository.saveEntry(new ProviderCacheEntry(UUID.randomUUID(), tenantId, providerKey, operation,
				requestKey(params), response, p.dataCategory(), now, now.plusSeconds(ttl), p.dataCategory()));
		return p.dataCategory();
	}

}
