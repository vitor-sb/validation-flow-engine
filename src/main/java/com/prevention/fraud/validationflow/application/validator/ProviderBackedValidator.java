package com.prevention.fraud.validationflow.application.validator;

import com.prevention.fraud.validationflow.application.provider.DataProvider;
import com.prevention.fraud.validationflow.application.provider.ProviderConfigService;
import com.prevention.fraud.validationflow.application.provider.ProviderException;
import java.util.LinkedHashMap;
import java.util.Map;
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

	public ProviderBackedValidator(String key, DataProvider provider, ProviderConfigService configs) {
		this.key = key;
		this.provider = provider;
		this.configs = configs;
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
		Map<String, Object> inputData = asMap(input.data().get("inputData"));
		Map<String, Object> query = new LinkedHashMap<>();
		asMap(params.get("query")).forEach((param, field) -> {
			Object value = inputData.get(String.valueOf(field));
			if (value == null) {
				throw new ValidatorException("MISSING_INPUT", "input field '" + field + "' is required by " + key, false);
			}
			query.put(param, value);
		});
		if (query.isEmpty()) {
			throw new ValidatorException("INVALID_CONFIG", "params.query is required by " + key, false);
		}
		Map<String, Object> data;
		try {
			data = provider.fetch(config, query);
		}
		catch (ProviderException e) {
			throw new ValidatorException(e.code(), e.getMessage(), e.retryable());
		}
		Map<String, Object> expect = asMap(params.get("expect"));
		boolean ok = expect.entrySet().stream()
				.allMatch(e -> String.valueOf(e.getValue()).equals(String.valueOf(data.get(e.getKey()))));
		return new ValidationResult(ok, data);
	}

	@Override
	public Map<String, Object> schema() {
		return Map.of("type", "object", "properties", Map.of("params", Map.of("type", "object", "properties", Map.of(
				"query", Map.of("type", "object", "description", "provider parameter -> inputData field"),
				"expect", Map.of("type", "object", "description", "output field -> required value")))));
	}

	@Override
	public Set<String> capabilities() {
		return Set.of("EXTERNAL_PROVIDER", "RETRY", "TIMEOUT");
	}

	private static Map<String, Object> asMap(Object o) {
		Map<String, Object> out = new LinkedHashMap<>();
		if (o instanceof Map<?, ?> m) {
			m.forEach((k, v) -> out.put(String.valueOf(k), v));
		}
		return out;
	}

}
