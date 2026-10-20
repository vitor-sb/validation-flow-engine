package com.prevention.fraud.validationflow.application.provider;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.prevention.fraud.validationflow.application.shared.CanonicalHash;

public class ProviderCache {

	private static final Logger log = LoggerFactory.getLogger(ProviderCache.class);

	/** Operation of a node that declares none. */
	public static final String OPERATION = "default";

	/** The operation a node's config declares, or {@link #OPERATION}. */
	public static String operationOf(Map<String, ?> nodeConfig) {
		return nodeConfig.get("operation") instanceof String o ? o : OPERATION;
	}

	/** Like {@link #operationOf(Map)}, but a node without {@code operation} resolves to the provider's only operation. */
	public static String operationOf(Map<String, ?> nodeConfig, DataProvider provider) {
		if (nodeConfig.get("operation") instanceof String o) {
			return o;
		}
		List<OperationSpec> ops = provider.operations();
		return ops.size() == 1 ? ops.get(0).key() : OPERATION;
	}

	public static final String HIT = "HIT", MISS = "MISS", EXPIRED = "EXPIRED", BYPASS = "BYPASS", DISABLED = "DISABLED",
			STALE_FALLBACK = "STALE_FALLBACK";

	/** Outcome of a lookup: {@code entry} is set only for HIT and EXPIRED. */
	public record Lookup(String status, ProviderCacheEntry entry) {

		public boolean hit() {
			return HIT.equals(status);
		}

		/** Copy of {@code out} with {@code cacheStatus}, {@code cacheOperation}, {@code cacheAgeSeconds} and (when one applied) {@code cachePolicy}. */
		public Map<String, Object> tag(Map<String, Object> out, String operation, String policy, Instant now) {
			Map<String, Object> tagged = new LinkedHashMap<>(out);
			tagged.put("cacheStatus", status);
			tagged.put("cacheOperation", operation);
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
	private final ProviderMetrics metrics;
	private final Map<String, DataProvider> providers;

	public ProviderCache(ProviderCacheRepository repository, CachePolicyService policies, Duration negativeTtl) {
		this(repository, policies, negativeTtl, ProviderMetrics.NONE);
	}

	public ProviderCache(ProviderCacheRepository repository, CachePolicyService policies, Duration negativeTtl,
			ProviderMetrics metrics) {
		this(repository, policies, negativeTtl, metrics, Map.of());
	}

	/** @param providers by key; each one may normalize the parameters that identify an entry ({@link DataProvider#cacheKeyParams}) */
	public ProviderCache(ProviderCacheRepository repository, CachePolicyService policies, Duration negativeTtl,
			ProviderMetrics metrics, Map<String, DataProvider> providers) {
		this.providers = providers;
		this.metrics = metrics;
		this.repository = repository;
		this.policies = policies;
		this.negativeTtl = negativeTtl;
	}

	/** Hash of the canonical parameters; the clear values are never stored. */
	public static String requestKey(Map<String, ?> params) {
		return CanonicalHash.sha256(params);
	}

	/**
	 * Hash of the (adapter-normalized) parameters named by the policy's {@code keyFields} (all of them when null). Empty, with a warning
	 * naming the field (never the value), when a key field is missing: a partial key is never built.
	 */
	private Optional<String> keyOf(String providerKey, String operation, ProviderCachePolicy policy, Map<String, ?> raw) {
		var provider = providers.get(providerKey);
		Map<String, ?> params = provider == null ? raw : provider.cacheKeyParams(operation, new LinkedHashMap<>(raw));
		if (policy == null || policy.keyFields() == null) {
			return Optional.of(requestKey(params));
		}
		Map<String, Object> key = new LinkedHashMap<>();
		for (String field : policy.keyFields()) {
			if (!params.containsKey(field)) {
				log.warn("cache disabled for provider={} operation={}: key field '{}' is missing", providerKey, operation, field);
				return Optional.empty();
			}
			key.put(field, params.get(field));
		}
		return Optional.of(requestKey(key));
	}

	public Optional<ProviderCacheEntry> find(String tenantId, String providerKey, String operation,
			Map<String, ?> params) {
		var policy = policies.resolve(tenantId, providerKey, operation).orElse(null);
		return find(tenantId, providerKey, operation, policy, params);
	}

	private Optional<ProviderCacheEntry> find(String tenantId, String providerKey, String operation,
			ProviderCachePolicy policy, Map<String, ?> params) {
		return keyOf(providerKey, operation, policy, params)
			.flatMap(k -> repository.findEntry(tenantId, providerKey, operation, k));
	}

	/** BYPASS skips the read (the fresh answer still refreshes the entry); no policy means DISABLED. */
	public Lookup lookup(String tenantId, String providerKey, String operation, Map<String, ?> params, boolean bypass,
			Instant now) {
		if (bypass) {
			metrics.cache(providerKey, "bypass");
			return new Lookup(BYPASS, null);
		}
		var policy = policies.resolve(tenantId, providerKey, operation);
		if (policy.isEmpty()) {
			return new Lookup(DISABLED, null);
		}
		var key = keyOf(providerKey, operation, policy.get(), params);
		if (key.isEmpty()) {
			return new Lookup(DISABLED, null);
		}
		var entry = repository.findEntry(tenantId, providerKey, operation, key.get());
		if (entry.isEmpty()) {
			metrics.cache(providerKey, "miss");
			return new Lookup(MISS, null);
		}
		var lookup = new Lookup(entry.get().validUntil().isAfter(now) ? HIT : EXPIRED, entry.get());
		metrics.cache(providerKey, lookup.status().toLowerCase());
		return lookup;
	}

	/**
	 * The stored answer to serve, flagged STALE_FALLBACK, when the provider failed for good: only if a policy allows
	 * {@code fallbackOnErrorAllowed} and an entry (valid or expired) exists. The caller must record the failure reason.
	 */
	public Optional<Lookup> staleFallback(String tenantId, String providerKey, String operation, Map<String, ?> params) {
		var policy = policies.resolve(tenantId, providerKey, operation).filter(ProviderCachePolicy::fallbackOnErrorAllowed);
		if (policy.isEmpty()) {
			return Optional.empty();
		}
		return find(tenantId, providerKey, operation, policy.get(), params).map(e -> {
			metrics.cache(providerKey, "stale_fallback");
			return new Lookup(STALE_FALLBACK, e);
		});
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
		var key = keyOf(providerKey, operation, p, params);
		if (key.isEmpty()) {
			return null;
		}
		long ttl = p.ttlSeconds();
		if (Boolean.FALSE.equals(response.get("found"))) {
			ttl = Math.min(ttl, negativeTtl.toSeconds());
		}
		repository.saveEntry(new ProviderCacheEntry(UUID.randomUUID(), tenantId, providerKey, operation,
				key.get(), response, p.dataCategory(), now, now.plusSeconds(ttl), p.dataCategory()));
		return p.dataCategory();
	}

}
