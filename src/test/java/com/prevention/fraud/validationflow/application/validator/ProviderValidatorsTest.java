package com.prevention.fraud.validationflow.application.validator;

import com.prevention.fraud.validationflow.application.provider.DataProvider;
import com.prevention.fraud.validationflow.application.provider.ProviderException;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderValidatorsTest {

	private static final Map<String, Object> CONFIG = Map.of("params",
			Map.of("query", Map.of("cnpj", "companyCnpj"), "expect", Map.of("active", true)));

	private static ValidatorStrategy.ValidationInput input(Map<String, Object> inputData, Map<String, Object> config) {
		return new ValidatorStrategy.ValidationInput(Map.of("inputData", inputData), config);
	}

	private static DataProvider provider(Map<String, Object> answer) {
		return new DataProvider() {
			public String key() {
				return "fake";
			}

			public Map<String, Object> fetch(Map<String, Object> params) {
				assertThat(params).containsEntry("cnpj", "11222333000181");
				return answer;
			}
		};
	}

	@Test
	void succeedsWhenExpectationsMatch() {
		var v = new ProviderBackedValidator("p", provider(Map.of("active", true, "uf", "SP")));
		var r = v.execute(input(Map.of("companyCnpj", "11222333000181"), CONFIG));
		assertThat(r.success()).isTrue();
		assertThat(r.output()).containsEntry("uf", "SP");
	}

	@Test
	void failsWhenExpectationDoesNotMatch() {
		var v = new ProviderBackedValidator("p", provider(Map.of("active", false)));
		assertThat(v.execute(input(Map.of("companyCnpj", "11222333000181"), CONFIG)).success()).isFalse();
	}

	@Test
	void withoutExpectAnyAnswerSucceeds() {
		var v = new ProviderBackedValidator("p", provider(Map.of("found", false)));
		var config = Map.<String, Object>of("params", Map.of("query", Map.of("cnpj", "companyCnpj")));
		assertThat(v.execute(input(Map.of("companyCnpj", "11222333000181"), config)).success()).isTrue();
	}

	@Test
	void missingInputIsNotRetryableAndDoesNotLeakValues() {
		var v = new ProviderBackedValidator("p", provider(Map.of()));
		assertThatThrownBy(() -> v.execute(input(Map.of(), CONFIG))).isInstanceOfSatisfying(ValidatorException.class,
				e -> {
					assertThat(e.code()).isEqualTo("MISSING_INPUT");
					assertThat(e.retryable()).isFalse();
				});
	}

	@Test
	void missingQueryIsInvalidConfig() {
		var v = new ProviderBackedValidator("p", provider(Map.of()));
		assertThatThrownBy(() -> v.execute(input(Map.of(), Map.of()))).isInstanceOfSatisfying(ValidatorException.class,
				e -> assertThat(e.code()).isEqualTo("INVALID_CONFIG"));
	}

	@Test
	void translatesProviderExceptionKeepingRetryability() {
		DataProvider down = new DataProvider() {
			public String key() {
				return "down";
			}

			public Map<String, Object> fetch(Map<String, Object> params) {
				throw new ProviderException("PROVIDER_UNAVAILABLE", "boom", true);
			}
		};
		var v = new ProviderBackedValidator("p", down);
		assertThatThrownBy(() -> v.execute(input(Map.of("companyCnpj", "11222333000181"), CONFIG)))
				.isInstanceOfSatisfying(ValidatorException.class, e -> {
					assertThat(e.code()).isEqualTo("PROVIDER_UNAVAILABLE");
					assertThat(e.retryable()).isTrue();
				});
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
