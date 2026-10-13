package com.prevention.fraud.validationflow.adapter.out.provider.brasilapi;

import com.prevention.fraud.validationflow.application.provider.ProviderException;
import com.prevention.fraud.validationflow.application.provider.ProviderConfig;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BrasilApiCnpjProviderTest {

	private static final String URL = "https://brasilapi.test/api/cnpj/v1/11222333000181";

	private static final ProviderConfig CONFIG = new ProviderConfig("t", "brasilapi-cnpj", true, null, null, null, null,
			"u", Instant.EPOCH);

	private MockRestServiceServer server;

	private BrasilApiCnpjProvider provider;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl("https://brasilapi.test/api");
		server = MockRestServiceServer.bindTo(builder).build();
		RestClient client = builder.build();
		provider = new BrasilApiCnpjProvider(c -> client);
	}

	private Map<String, Object> fetch() {
		return provider.fetch(CONFIG, Map.of("cnpj", "11.222.333/0001-81"));
	}

	@Test
	void normalizesTheAnswerAndDropsPartners() {
		server.expect(requestTo(URL)).andExpect(method(HttpMethod.GET)).andRespond(withSuccess("""
				{"cnpj":"11222333000181","razao_social":"ACME LTDA","situacao_cadastral":2,
				 "descricao_situacao_cadastral":"ATIVA","uf":"SP","municipio":"SAO PAULO","cnae_fiscal":6201501,
				 "data_inicio_atividade":"2010-01-01","qsa":[{"nome_socio":"FULANO"}]}""", MediaType.APPLICATION_JSON));
		var out = fetch();
		assertThat(out).containsEntry("found", true).containsEntry("active", true).containsEntry("situacao", "ATIVA")
				.containsEntry("razaoSocial", "ACME LTDA").doesNotContainKey("qsa");
		server.verify();
	}

	@Test
	void inactiveCompanyIsNotActive() {
		server.expect(requestTo(URL)).andRespond(withSuccess(
				"{\"situacao_cadastral\":8,\"descricao_situacao_cadastral\":\"BAIXADA\"}", MediaType.APPLICATION_JSON));
		assertThat(fetch()).containsEntry("active", false).containsEntry("situacao", "BAIXADA");
	}

	@Test
	void notFoundIsAnAnswerNotAnError() {
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));
		assertThat(fetch()).containsEntry("found", false);
	}

	@Test
	void classifiesFailures() {
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
		assertThatThrownBy(this::fetch).isInstanceOfSatisfying(ProviderException.class, e -> {
			assertThat(e.code()).isEqualTo("PROVIDER_RATE_LIMITED");
			assertThat(e.retryable()).isTrue();
		});
		server.reset();
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
		assertThatThrownBy(this::fetch).isInstanceOfSatisfying(ProviderException.class, e -> {
			assertThat(e.code()).isEqualTo("PROVIDER_UNAVAILABLE");
			assertThat(e.retryable()).isTrue();
		});
		server.reset();
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));
		assertThatThrownBy(this::fetch).isInstanceOfSatisfying(ProviderException.class, e -> {
			assertThat(e.code()).isEqualTo("PROVIDER_REJECTED");
			assertThat(e.retryable()).isFalse();
		});
	}

	@Test
	void rejectsInvalidCnpjWithoutCallingOut() {
		assertThatThrownBy(() -> provider.fetch(CONFIG, Map.of("cnpj", "123"))).isInstanceOfSatisfying(ProviderException.class,
				e -> {
					assertThat(e.code()).isEqualTo("INVALID_INPUT");
					assertThat(e.retryable()).isFalse();
				});
		server.verify();
	}

}
