package com.prevention.fraud.validationflow.adapter.out.provider.brasilapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.prevention.fraud.validationflow.application.provider.CachePolicyService;
import com.prevention.fraud.validationflow.application.provider.ProviderCache;
import com.prevention.fraud.validationflow.application.provider.ProviderCacheEntry;
import com.prevention.fraud.validationflow.application.provider.ProviderCachePolicy;
import com.prevention.fraud.validationflow.application.provider.ProviderCacheRepository;
import com.prevention.fraud.validationflow.application.provider.ProviderConfig;
import com.prevention.fraud.validationflow.application.provider.ProviderConfigService;
import com.prevention.fraud.validationflow.application.validator.ProviderBackedValidator;
import com.prevention.fraud.validationflow.application.validator.ValidatorStrategy.ValidationInput;
import com.sun.net.httpserver.HttpServer;

/** BrasilAPI with two operations against a local simulated server (never the real API). */
class BrasilApiProviderTest {

	private static final ProviderConfig CONFIG = new ProviderConfig("t", "brasilapi", true, null, null, null, null, "u",
			Instant.EPOCH);

	private HttpServer server;

	private final List<String> requests = new ArrayList<>();

	private BrasilApiProvider provider;

	@BeforeEach
	void setUp() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", ex -> {
			String path = ex.getRequestURI().getPath();
			requests.add(path);
			String body = path.endsWith("99999999") ? null
					: path.startsWith("/cnpj/v1/")
					? "{\"razao_social\":\"ACME\",\"situacao_cadastral\":2,\"descricao_situacao_cadastral\":\"ATIVA\",\"uf\":\"SP\"}"
					: path.startsWith("/cep/v2/") ? "{\"state\":\"SP\",\"city\":\"Sao Paulo\",\"neighborhood\":\"Se\",\"street\":\"Praca da Se\"}"
							: null;
			byte[] bytes = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().add("Content-Type", "application/json");
			ex.sendResponseHeaders(body == null ? 404 : 200, bytes.length == 0 ? -1 : bytes.length);
			if (bytes.length > 0) {
				ex.getResponseBody().write(bytes);
			}
			ex.close();
		});
		server.start();
		RestClient client = RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
		provider = new BrasilApiProvider(c -> client);
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	@Test
	void declaresCnpjAndCep() {
		assertThat(provider.operations()).extracting(o -> o.key()).containsExactly("cnpj", "cep");
	}

	@Test
	void eachOperationHitsItsEndpointAndNormalizesItsOwnFields() {
		var cnpj = provider.fetch(CONFIG, "cnpj", Map.of("cnpj", "11.222.333/0001-81"));
		var cep = provider.fetch(CONFIG, "cep", Map.of("cep", "01001-000"));
		assertThat(requests).containsExactly("/cnpj/v1/11222333000181", "/cep/v2/01001000");
		assertThat(cnpj).containsEntry("active", true).containsEntry("razaoSocial", "ACME").doesNotContainKey("logradouro");
		assertThat(cep).containsEntry("logradouro", "Praca da Se").containsEntry("cidade", "Sao Paulo")
				.doesNotContainKey("razaoSocial");
	}

	@Test
	void unknownCepIsNotFound() {
		assertThat(provider.fetch(CONFIG, "cep", Map.of("cep", "99999999"))).containsEntry("found", false)
				.containsEntry("cep", "99999999");
	}

	@Test
	void cacheKeyIsDigitsOnlyPerOperation() {
		assertThat(provider.cacheKeyParams("cnpj", Map.of("cnpj", "11.222.333/0001-81"))).containsEntry("cnpj", "11222333000181");
		assertThat(provider.cacheKeyParams("cep", Map.of("cep", "01001-000"))).containsEntry("cep", "01001000");
	}

	@Test
	void operationsHaveSeparateCacheEntriesAndPolicies() {
		var store = new HashMap<String, ProviderCacheEntry>();
		var policies = new HashMap<String, ProviderCachePolicy>();
		var repo = mock(ProviderCacheRepository.class);
		when(repo.findPolicy(any(), any(), any())).thenAnswer(i -> Optional.ofNullable(policies.get(i.getArgument(2))));
		when(repo.findEntry(any(), any(), any(), any())).thenAnswer(
				i -> Optional.ofNullable(store.get(i.getArgument(2) + "|" + i.getArgument(3))));
		org.mockito.Mockito.doAnswer(i -> {
			ProviderCacheEntry e = i.getArgument(0);
			store.put(e.operation() + "|" + e.requestKey(), e);
			return null;
		}).when(repo).saveEntry(any());
		var policyService = new CachePolicyService(repo, Map.of("LOW_VOLATILITY", Duration.ofHours(1)), Duration.ofHours(2));
		var cache = new ProviderCache(repo, policyService, Duration.ofMinutes(5),
				com.prevention.fraud.validationflow.application.provider.ProviderMetrics.NONE, Map.of("brasilapi", provider));
		var configs = mock(ProviderConfigService.class);
		when(configs.resolve("t", "brasilapi")).thenReturn(Optional.of(CONFIG));
		var validator = new ProviderBackedValidator("brasilapi", provider, configs, cache);

		// only cnpj has a policy; both operations use a parameter named like the operation, never sharing an entry
		policies.put("cnpj", new ProviderCachePolicy("t", "brasilapi", "cnpj", "LOW_VOLATILITY", null, false, null, null, null, null));
		var cnpjNode = Map.<String, Object>of("operation", "cnpj", "params", Map.of("query", Map.of("cnpj", "doc")));
		var cepNode = Map.<String, Object>of("operation", "cep", "params", Map.of("query", Map.of("cep", "doc")));
		var cnpjData = Map.<String, Object>of("inputData", Map.of("doc", "11222333000181"));
		var cepData = Map.<String, Object>of("inputData", Map.of("doc", "01001000"));

		assertThat(validator.execute(new ValidationInput(cnpjData, cnpjNode, "t")).output()).containsEntry("cacheStatus", "MISS");
		assertThat(validator.execute(new ValidationInput(cnpjData, cnpjNode, "t")).output()).containsEntry("cacheStatus", "HIT");
		assertThat(validator.execute(new ValidationInput(cepData, cepNode, "t")).output()).containsEntry("cacheStatus", "DISABLED");
		assertThat(requests).containsExactly("/cnpj/v1/11222333000181", "/cep/v2/01001000");
		assertThat(store.keySet()).allMatch(k -> k.startsWith("cnpj|"));

		policies.put("cep", new ProviderCachePolicy("t", "brasilapi", "cep", "LOW_VOLATILITY", null, false, null, null, null, null));
		assertThat(validator.execute(new ValidationInput(cepData, cepNode, "t")).output()).containsEntry("cacheStatus", "MISS");
		assertThat(validator.execute(new ValidationInput(cepData, cepNode, "t")).output()).containsEntry("cacheStatus", "HIT");
		assertThat(store).hasSize(2);
	}

}
