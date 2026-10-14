package com.prevention.fraud.validationflow.application.validator;

import com.prevention.fraud.validationflow.application.provider.DataProvider;
import com.prevention.fraud.validationflow.application.provider.ProviderCache;
import com.prevention.fraud.validationflow.application.provider.ProviderConfigService;
import com.prevention.fraud.validationflow.application.provider.ProviderException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Adapts a {@link DataProvider} to a {@link ValidatorStrategy}. Node config ({@code params}):
 * <ul>
 * <li>{@code query}: provider parameter name to the {@code inputData} field that feeds it, e.g. {@code {"cnpj": "companyCnpj"}};</li>
 * <li>{@code expect} (optional): output field to the value it must have, e.g. {@code {"active": true}}. The step
 * succeeds only when every expectation matches (compared as strings); without it, any provider answer succeeds.</li>
 * </ul>
 * The provider's configuration is resolved per execution by (tenant, key); a missing or disabled row fails with
 * {@code PROVIDER_DISABLED}.
 */
public class ProviderBackedValidator implements ValidatorStrategy {

	private final String key;

	private final DataProvider provider;

	private final ProviderConfigService configs;

	private final ProviderCache cache;

	public ProviderBackedValidator(String key, DataProvider provider, ProviderConfigService configs) {
		this(key, provider, configs, null);
	}

	/** {@code cache} may be null: no lookup, no {@code cacheStatus} in the output. */
	public ProviderBackedValidator(String key, DataProvider provider, ProviderConfigService configs, ProviderCache cache) {
		this.key = key;
		this.provider = provider;
		this.configs = configs;
		this.cache = cache;
	}

	@Override
	public String key() {
		return key;
	}

	@Override
	public ValidationResult execute(ValidationInput input) {
		var config = configs.resolve(input.tenantId(), provider.key())
				.orElseThrow(() -> new ValidatorException("PROVIDER_DISABLED", key + " is not enabled for this tenant", false));
		Map<String, Object> params = asMap(input.config().get("params"));
		Map<String, Object> query = query(key, params.get("query"), asMap(input.data().get("inputData")));
		Instant now = Instant.now();
		var lookup = cache == null ? null : cache.lookup(input.tenantId(), provider.key(), ProviderCache.OPERATION, query,
				"BYPASS".equals(input.config().get("cache")), now);
		Map<String, Object> data;
		String policy = null;
		if (lookup != null && lookup.hit()) {
			data = lookup.entry().response();
		}
		else {
			try {
				data = provider.fetch(config, query);
			}
			catch (ProviderException e) {
				throw new ValidatorException(e.code(), e.getMessage(), e.retryable());
			}
			if (cache != null) {
				policy = cache.store(input.tenantId(), provider.key(), ProviderCache.OPERATION, query, data, now);
			}
		}
		boolean ok = matches(params.get("expect"), data);
		return new ValidationResult(ok, lookup == null ? data : lookup.tag(data, policy, now));
	}

	@Override
	public Optional<ValidationResult> fallback(ValidationInput input, String errorCode) {
		if (cache == null) {
			return Optional.empty();
		}
		Map<String, Object> params = asMap(input.config().get("params"));
		Map<String, Object> query = query(key, params.get("query"), asMap(input.data().get("inputData")));
		return cache.staleFallback(input.tenantId(), provider.key(), ProviderCache.OPERATION, query).map(l -> {
			Map<String, Object> data = l.entry().response();
			Map<String, Object> out = new LinkedHashMap<>(l.tag(data, null, Instant.now()));
			out.put("fallbackReason", errorCode);
			return new ValidationResult(matches(params.get("expect"), data), out);
		});
	}

	@Override
	public Map<String, Object> schema() {
		return Map.of("type", "object", "properties", Map.of("params", Map.of("type", "object", "properties", Map.of(
				"query", Map.of("type", "object", "description", "provider parameter -> inputData field"),
				"expect", Map.of("type", "object", "description", "output field -> required value")))));
	}

	@Override
	public String providerKey() {
		return provider.key();
	}

	@Override
	public Set<String> capabilities() {
		return Set.of("EXTERNAL_PROVIDER", "RETRY", "TIMEOUT");
	}

	/** Provider parameters from {@code inputData} per the node's {@code query} mapping; {@code owner} names the node in errors. */
	public static Map<String, Object> query(String owner, Object queryConfig, Map<String, Object> inputData) {
		Map<String, Object> query = new LinkedHashMap<>();
		asMap(queryConfig).forEach((param, field) -> {
			Object value = inputData.get(String.valueOf(field));
			if (value == null) {
				throw new ValidatorException("MISSING_INPUT", "input field '" + field + "' is required by " + owner, false);
			}
			query.put(param, value);
		});
		if (query.isEmpty()) {
			throw new ValidatorException("INVALID_CONFIG", "params.query is required by " + owner, false);
		}
		return query;
	}

	/** True when every {@code expect} entry equals the provider's field (compared as strings). */
	public static boolean matches(Object expect, Map<String, Object> data) {
		return asMap(expect).entrySet().stream()
				.allMatch(e -> String.valueOf(e.getValue()).equals(String.valueOf(data.get(e.getKey()))));
	}

	private static Map<String, Object> asMap(Object o) {
		Map<String, Object> out = new LinkedHashMap<>();
		if (o instanceof Map<?, ?> m) {
			m.forEach((k, v) -> out.put(String.valueOf(k), v));
		}
		return out;
	}

}
