package com.prevention.fraud.validationflow.adapter.out.provider.brasilapi;

import com.prevention.fraud.validationflow.application.provider.DataProvider;
import com.prevention.fraud.validationflow.application.provider.ProviderConfig;
import com.prevention.fraud.validationflow.application.provider.ProviderException;
import com.prevention.fraud.validationflow.domain.document.BrazilianDocuments;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Company registry lookup on the public, free BrasilAPI ({@code GET /cnpj/v1/{cnpj}}). It has no SLA and rate limits,
 * so 429/5xx/network errors are reported as retryable. Partners (sócios) are deliberately not copied to the output.
 */
public class BrasilApiCnpjProvider implements DataProvider {

	/** BrasilAPI's {@code situacao_cadastral} code for an active company. */
	private static final int ACTIVE = 2;

	private final Function<ProviderConfig, RestClient> clients;

	/** @param clients returns the (cached) client for a tenant's configuration */
	public BrasilApiCnpjProvider(Function<ProviderConfig, RestClient> clients) {
		this.clients = clients;
	}

	@Override
	public String key() {
		return "brasilapi-cnpj";
	}

	@Override
	public Map<String, Object> fetch(ProviderConfig config, Map<String, Object> params) {
		String cnpj = BrazilianDocuments.normalize(String.valueOf(params.get("cnpj")));
		if (!BrazilianDocuments.isValidCnpj(cnpj)) {
			throw new ProviderException("INVALID_INPUT", "cnpj is not valid", false);
		}
		Map<String, Object> body;
		try {
			body = clients.apply(config).get().uri("/cnpj/v1/{cnpj}", cnpj).retrieve().body(new ParameterizedTypeReference<>() {
			});
		}
		catch (RestClientResponseException e) {
			return onStatus(e.getStatusCode(), cnpj, e);
		}
		catch (ResourceAccessException e) {
			throw new ProviderException("PROVIDER_UNAVAILABLE", "brasilapi unreachable or timed out", true, e);
		}
		if (body == null) {
			throw new ProviderException("PROVIDER_BAD_RESPONSE", "brasilapi returned an empty body", true);
		}
		return normalize(cnpj, body);
	}

	private static Map<String, Object> onStatus(HttpStatusCode status, String cnpj, Exception cause) {
		int code = status.value();
		if (code == 404) {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("found", false);
			out.put("cnpj", cnpj);
			return out;
		}
		if (code == 429) {
			throw new ProviderException("PROVIDER_RATE_LIMITED", "brasilapi rate limit reached", true, cause);
		}
		if (status.is5xxServerError()) {
			throw new ProviderException("PROVIDER_UNAVAILABLE", "brasilapi answered " + code, true, cause);
		}
		throw new ProviderException("PROVIDER_REJECTED", "brasilapi rejected the request with " + code, false, cause);
	}

	private static Map<String, Object> normalize(String cnpj, Map<String, Object> body) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("found", true);
		out.put("cnpj", cnpj);
		out.put("razaoSocial", body.get("razao_social"));
		out.put("situacao", body.get("descricao_situacao_cadastral"));
		out.put("active", body.get("situacao_cadastral") instanceof Number n && n.intValue() == ACTIVE);
		out.put("uf", body.get("uf"));
		out.put("municipio", body.get("municipio"));
		out.put("cnaeFiscal", body.get("cnae_fiscal"));
		out.put("dataInicioAtividade", body.get("data_inicio_atividade"));
		return out;
	}

}
