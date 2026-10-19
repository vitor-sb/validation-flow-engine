package com.prevention.fraud.validationflow.application.provider;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Resolves the cache policy that applies to (tenant, provider, operation); the TTL always comes from policy or configured defaults. */
public class CachePolicyService {

	public static final String LOW = "LOW_VOLATILITY", MEDIUM = "MEDIUM_VOLATILITY", HIGH = "HIGH_VOLATILITY",
			REAL_TIME = "REAL_TIME", CUSTOM = "CUSTOM";

	private final ProviderCacheRepository repository;
	private final Map<String, Duration> defaultTtls;
	private final Duration maxTtl;

	public CachePolicyService(ProviderCacheRepository repository, Map<String, Duration> defaultTtls, Duration maxTtl) {
		this.repository = repository;
		this.defaultTtls = defaultTtls;
		this.maxTtl = maxTtl;
	}

	/** The policy with {@code ttlSeconds} set to the effective TTL; empty when nothing may be cached (no policy, REAL_TIME, incomplete CUSTOM). */
	public Optional<ProviderCachePolicy> resolve(String tenantId, String providerKey, String operation) {
		return repository.findPolicy(tenantId, providerKey, operation).filter(p -> problem(p) == null)
			.filter(p -> !REAL_TIME.equals(p.dataCategory())).map(this::withEffectiveTtl);
	}

	public List<ProviderCachePolicy> list(String tenantId, String providerKey) {
		return repository.findPolicies(tenantId, providerKey);
	}

	/** @throws IllegalArgumentException when the policy is not acceptable */
	public void save(ProviderCachePolicy policy, String actor) {
		var problem = problem(policy);
		if (problem != null) {
			throw new IllegalArgumentException(problem);
		}
		repository.savePolicy(policy, actor);
	}

	private ProviderCachePolicy withEffectiveTtl(ProviderCachePolicy p) {
		var ttl = p.ttlSeconds() != null ? Duration.ofSeconds(p.ttlSeconds()) : defaultTtls.get(p.dataCategory());
		var effective = ttl.compareTo(maxTtl) < 0 ? ttl : maxTtl;
		return new ProviderCachePolicy(p.tenantId(), p.providerKey(), p.operation(), p.dataCategory(),
				(int) effective.toSeconds(), p.fallbackOnErrorAllowed(), p.justification(), p.owner(), p.reviewDate(), p.keyFields());
	}

	private static String problem(ProviderCachePolicy p) {
		if (p.dataCategory() == null || !List.of(LOW, MEDIUM, HIGH, REAL_TIME, CUSTOM).contains(p.dataCategory())) {
			return "dataCategory must be one of LOW_VOLATILITY, MEDIUM_VOLATILITY, HIGH_VOLATILITY, REAL_TIME, CUSTOM";
		}
		if (p.ttlSeconds() != null && p.ttlSeconds() <= 0) {
			return "ttlSeconds must be positive";
		}
		if (CUSTOM.equals(p.dataCategory()) && (p.ttlSeconds() == null || blank(p.justification()) || blank(p.owner())
				|| p.reviewDate() == null)) {
			return "CUSTOM requires ttlSeconds, justification, owner and reviewDate";
		}
		var keys = p.keyFields();
		if (keys != null && (keys.isEmpty() || keys.stream().anyMatch(k -> blank(k)) || keys.stream().distinct().count() != keys.size())) {
			return "keyFields must be non-empty, without blank or duplicated names (omit it for all parameters)";
		}
		return null;
	}

	private static boolean blank(String s) {
		return s == null || s.isBlank();
	}

}
