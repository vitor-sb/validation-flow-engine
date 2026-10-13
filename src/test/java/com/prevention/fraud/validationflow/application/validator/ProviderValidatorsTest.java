package com.prevention.fraud.validationflow.application.validator;

import com.prevention.fraud.validationflow.application.provider.DataProvider;
import com.prevention.fraud.validationflow.application.provider.ProviderConfig;
import com.prevention.fraud.validationflow.application.provider.ProviderConfigRepository;
import com.prevention.fraud.validationflow.application.provider.ProviderConfigService;
import com.prevention.fraud.validationflow.application.provider.ProviderException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderValidatorsTest {

	private static final Map<String, Object> CONFIG = Map.of("params",
			Map.of("query", Map.of("cnpj", "companyCnpj"), "expect", Map.of("active", true)));

	private static ValidatorStrategy.ValidationInput input(Map<String, Object> inputData, Map<String, Object> config) {
		return new ValidatorStrategy.ValidationInput(Map.of("inputData", inputData), config, "A");
	}

	/** Tenant "A" has "p" enabled; tenant "B" has it disabled; nobody else has a row. */
	private static ProviderConfigService enabled() {
		var rows = List.of(new ProviderConfig("A", "fake", true, null, null, null, null, "u", Instant.EPOCH),
				new ProviderConfig("B", "fake", false, null, null, null, null, "u", Instant.EPOCH));
		ProviderConfigRepository repo = new ProviderConfigRepository() {
			public Optional<ProviderConfig> find(String t, String k) {
				return rows.stream().filter(r -> r.tenantId().equals(t) && r.providerKey().equals(k)).findFirst();
			}

			public List<ProviderConfig> findAll(String t) {
				return rows.stream().filter(r -> r.tenantId().equals(t)).toList();
			}

			public void save(ProviderConfig c) {
			}
		};
		return new ProviderConfigService(repo, Duration.ofSeconds(30), Clock.systemUTC());
	}

	private static DataProvider provider(Map<String, Object> answer) {
		return new DataProvider() {
			public String key() {
				return "fake";
			}

			public Map<String, Object> fetch(ProviderConfig config, Map<String, Object> params) {
				assertThat(params).containsEntry("cnpj", "11222333000181");
				return answer;
			}
		};
	}

	@Test
	void succeedsWhenExpectationsMatch() {
		var v = new ProviderBackedValidator("p", provider(Map.of("active", true, "uf", "SP")), enabled());
		var r = v.execute(input(Map.of("companyCnpj", "11222333000181"), CONFIG));
		assertThat(r.success()).isTrue();
		assertThat(r.output()).containsEntry("uf", "SP");
	}

	@Test
	void failsWhenExpectationDoesNotMatch() {
		var v = new ProviderBackedValidator("p", provider(Map.of("active", false)), enabled());
		assertThat(v.execute(input(Map.of("companyCnpj", "11222333000181"), CONFIG)).success()).isFalse();
	}

	@Test
	void withoutExpectAnyAnswerSucceeds() {
		var v = new ProviderBackedValidator("p", provider(Map.of("found", false)), enabled());
		var config = Map.<String, Object>of("params", Map.of("query", Map.of("cnpj", "companyCnpj")));
		assertThat(v.execute(input(Map.of("companyCnpj", "11222333000181"), config)).success()).isTrue();
	}

	@Test
	void missingInputIsNotRetryableAndDoesNotLeakValues() {
		var v = new ProviderBackedValidator("p", provider(Map.of()), enabled());
		assertThatThrownBy(() -> v.execute(input(Map.of(), CONFIG))).isInstanceOfSatisfying(ValidatorException.class,
				e -> {
					assertThat(e.code()).isEqualTo("MISSING_INPUT");
					assertThat(e.retryable()).isFalse();
				});
	}

	@Test
	void missingQueryIsInvalidConfig() {
		var v = new ProviderBackedValidator("p", provider(Map.of()), enabled());
		assertThatThrownBy(() -> v.execute(input(Map.of(), Map.of()))).isInstanceOfSatisfying(ValidatorException.class,
				e -> assertThat(e.code()).isEqualTo("INVALID_CONFIG"));
	}

	@Test
	void translatesProviderExceptionKeepingRetryability() {
		DataProvider down = new DataProvider() {
			public String key() {
				return "fake";
			}

			public Map<String, Object> fetch(ProviderConfig config, Map<String, Object> params) {
				throw new ProviderException("PROVIDER_UNAVAILABLE", "boom", true);
			}
		};
		var v = new ProviderBackedValidator("p", down, enabled());
		assertThatThrownBy(() -> v.execute(input(Map.of("companyCnpj", "11222333000181"), CONFIG)))
				.isInstanceOfSatisfying(ValidatorException.class, e -> {
					assertThat(e.code()).isEqualTo("PROVIDER_UNAVAILABLE");
					assertThat(e.retryable()).isTrue();
				});
	}

	@Test
	void disabledOrUnconfiguredTenantFailsWithProviderDisabled() {
		var v = new ProviderBackedValidator("p", provider(Map.of("active", true)), enabled());
		var data = Map.<String, Object>of("companyCnpj", "11222333000181");
		for (String tenant : List.of("B", "C")) {
			var in = new ValidatorStrategy.ValidationInput(Map.of("inputData", data), CONFIG, tenant);
			assertThatThrownBy(() -> v.execute(in)).isInstanceOfSatisfying(ValidatorException.class, e -> {
				assertThat(e.code()).isEqualTo("PROVIDER_DISABLED");
				assertThat(e.retryable()).isFalse();
			});
		}
		assertThat(v.execute(input(data, CONFIG)).success()).isTrue();
	}

	@Test
	void checksumValidators() {
		var cfg = Map.<String, Object>of("params", Map.of("field", "doc"));
		assertThat(DocumentChecksumValidator.cpf().execute(input(Map.of("doc", "529.982.247-25"), cfg)).success()).isTrue();
		assertThat(DocumentChecksumValidator.cpf().execute(input(Map.of("doc", "529.982.247-24"), cfg)).success()).isFalse();
		assertThat(DocumentChecksumValidator.cnpj().execute(input(Map.of("doc", "11.222.333/0001-81"), cfg)).success()).isTrue();
		assertThatThrownBy(() -> DocumentChecksumValidator.cnpj().execute(input(Map.of(), cfg)))
				.isInstanceOfSatisfying(ValidatorException.class, e -> assertThat(e.code()).isEqualTo("MISSING_INPUT"));
		assertThatThrownBy(() -> DocumentChecksumValidator.cnpj().execute(input(Map.of("doc", "x"), Map.of())))
				.isInstanceOfSatisfying(ValidatorException.class, e -> assertThat(e.code()).isEqualTo("INVALID_CONFIG"));
	}

}
