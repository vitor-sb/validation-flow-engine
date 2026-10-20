package com.prevention.fraud.validationflow.application.flow;

import com.prevention.fraud.validationflow.application.provider.DataProvider;
import com.prevention.fraud.validationflow.application.provider.OperationSpec;
import com.prevention.fraud.validationflow.application.provider.ProviderCache;
import com.prevention.fraud.validationflow.application.provider.ProviderConfig;
import com.prevention.fraud.validationflow.application.validator.ProviderBackedValidator;
import com.prevention.fraud.validationflow.application.validator.ValidatorRegistry;
import com.prevention.fraud.validationflow.domain.flow.GraphValidator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OperationActivationTest {

	private static DataProvider provider(String key, OperationSpec... ops) {
		return new DataProvider() {
			public String key() {
				return key;
			}

			public Map<String, Object> fetch(ProviderConfig c, Map<String, Object> p) {
				return Map.of();
			}

			@Override
			public List<OperationSpec> operations() {
				return ops.length == 0 ? DataProvider.super.operations() : List.of(ops);
			}
		};
	}

	private static final DataProvider ONE = provider("one", new OperationSpec("cnpj", Set.of("cnpj"), Set.of("x")));

	private static final DataProvider TWO = provider("two", new OperationSpec("cnpj", Set.of("cnpj"), Set.of()),
			new OperationSpec("cep", Set.of("cep"), Set.of()));

	private static final DataProvider LEGACY = provider("legacy");

	private final FlowService flows = new FlowService(null, new GraphValidator(t -> true),
			new ValidatorRegistry(List.of(new ProviderBackedValidator("v-one", ONE, null),
					new ProviderBackedValidator("v-two", TWO, null), new ProviderBackedValidator("v-legacy", LEGACY, null))),
			null, Map.of("one", ONE, "two", TWO, "legacy", LEGACY));

	/** One VALIDATION node "n" (params.query) using the validator, with optional operation. */
	private List<String> codes(String validator, String operation, String... queryParams) {
		Map<String, Object> query = new java.util.LinkedHashMap<>();
		for (String p : queryParams) {
			query.put(p, "f");
		}
		Map<String, Object> cfg = new java.util.LinkedHashMap<>(
				Map.of("validatorType", validator, "params", Map.of("query", query)));
		if (operation != null) {
			cfg.put("operation", operation);
		}
		var graph = Map.<String, Object>of("nodes", Map.of("n", Map.of("type", "VALIDATION", "config", cfg)));
		return flows.operationProblems(graph).stream().map(GraphValidator.GraphError::code).toList();
	}

	@Test
	void singleOperationIsOptionalAndResolved() {
		assertThat(codes("v-one", null, "cnpj")).isEmpty();
		assertThat(codes("v-one", "cnpj", "cnpj", "x")).isEmpty();
		assertThat(ProviderCache.operationOf(Map.of(), ONE)).isEqualTo("cnpj");
		assertThat(ProviderCache.operationOf(Map.of("operation", "cnpj"), TWO)).isEqualTo("cnpj");
	}

	@Test
	void severalOperationsRequireOperationAndNeverDefault() {
		assertThat(codes("v-two", null, "cnpj")).containsExactly("OPERATION_REQUIRED");
		assertThat(codes("v-two", "cep", "cep")).isEmpty();
	}

	@Test
	void unknownOperationListsValidOnes() {
		var graph = Map.<String, Object>of("nodes", Map.of("n", Map.of("type", "VALIDATION",
				"config", Map.of("validatorType", "v-two", "operation", "nope", "params", Map.of("query", Map.of("cep", "f"))))));
		var errors = flows.operationProblems(graph);
		assertThat(errors).extracting(GraphValidator.GraphError::code).containsExactly("UNKNOWN_OPERATION");
		assertThat(errors.get(0).message()).contains("cnpj", "cep");
	}

	@Test
	void parametersAreChecked() {
		assertThat(codes("v-one", null)).containsExactly("MISSING_PARAMETER");
		assertThat(codes("v-one", null, "cnpj", "y")).containsExactly("UNKNOWN_PARAMETER");
		assertThat(codes("v-two", "cep", "cnpj")).containsExactly("MISSING_PARAMETER");
	}

	@Test
	void legacyProviderAcceptsAnything() {
		assertThat(codes("v-legacy", null, "whatever")).isEmpty();
		assertThat(ProviderCache.operationOf(Map.of(), LEGACY)).isEqualTo("default");
	}

	@Test
	void externalCallUsesTopLevelQuery() {
		var graph = Map.<String, Object>of("nodes", Map.of("n", Map.of("type", "EXTERNAL_CALL",
				"config", Map.of("providerKey", "two", "query", Map.of("cnpj", "f")))));
		assertThat(flows.operationProblems(graph)).extracting(GraphValidator.GraphError::code)
				.containsExactly("OPERATION_REQUIRED");
	}

}
