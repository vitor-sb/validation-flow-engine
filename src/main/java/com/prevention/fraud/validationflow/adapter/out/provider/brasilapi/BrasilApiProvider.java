package com.prevention.fraud.validationflow.adapter.out.provider.brasilapi;

import com.prevention.fraud.validationflow.application.provider.CallbackHandle;
import com.prevention.fraud.validationflow.application.provider.DataProvider;
import com.prevention.fraud.validationflow.application.provider.OperationSpec;
import com.prevention.fraud.validationflow.application.provider.ProviderConfig;
import com.prevention.fraud.validationflow.application.provider.ProviderException;
import com.prevention.fraud.validationflow.application.provider.ProviderResult;
import com.prevention.fraud.validationflow.domain.document.BrazilianDocuments;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * The public, free BrasilAPI with two operations: {@code cnpj} ({@code GET /cnpj/v1/{cnpj}}) and {@code cep}
 * ({@code GET /cep/v2/{cep}}). It has no SLA and rate limits, so 429/5xx/network errors are reported as retryable.
 * Each operation copies only its decision fields (partners/sócios are deliberately dropped).
 */
public class BrasilApiProvider implements DataProvider {

	private static final String CNPJ = "cnpj", CEP = "cep";

	/** BrasilAPI's {@code situacao_cadastral} code for an active company. */
	private static final int ACTIVE = 2;

	private final Function<ProviderConfig, RestClient> clients;

	/** @param clients returns the (cached) client for a tenant's configuration */
	public BrasilApiProvider(Function<ProviderConfig, RestClient> clients) {
		this.clients = clients;
	}

	@Override
	public String key() {
		return "brasilapi";
	}

	@Override
	public List<OperationSpec> operations() {
		return List.of(new OperationSpec(CNPJ, Set.of(CNPJ), Set.of()), new OperationSpec(CEP, Set.of(CEP), Set.of()));
	}

	@Override
	public Map<String, Object> cacheKeyParams(String operation, Map<String, Object> params) {
		if (!(params.get(operation) instanceof String value)) {
			return params;
		}
		Map<String, Object> out = new LinkedHashMap<>(params);
		out.put(operation, BrazilianDocuments.normalize(value));
		return out;
	}

	@Override
	public Map<String, Object> fetch(ProviderConfig config, Map<String, Object> params) {
		throw new UnsupportedOperationException("brasilapi needs an operation: " + CNPJ + " or " + CEP);
	}

	@Override
	public Map<String, Object> fetch(ProviderConfig config, String operation, Map<String, Object> params) {
		return switch (operation) {
			case CNPJ -> cnpj(config, params);
			case CEP -> cep(config, params);
			default -> throw new ProviderException("INVALID_INPUT", "unknown operation " + operation, false);
		};
	}

	// A sync adapter must route the operation through start too: the legacy forms would drop it.
	@Override
	public ProviderResult start(ProviderConfig config, String operation, Map<String, Object> params) {
		return new ProviderResult.Done(fetch(config, operation, params));
	}

	@Override
	public ProviderResult start(ProviderConfig config, String operation, Map<String, Object> params, CallbackHandle callback) {
		return start(config, operation, params);
	}

	private Map<String, Object> cnpj(ProviderConfig config, Map<String, Object> params) {
		String cnpj = BrazilianDocuments.normalize(String.valueOf(params.get(CNPJ)));
		if (!BrazilianDocuments.isValidCnpj(cnpj)) {
			throw new ProviderException("INVALID_INPUT", "cnpj is not valid", false);
		}
		Map<String, Object> body = get(config, "/cnpj/v1/{cnpj}", cnpj);
		if (body == null) {
			return notFound(CNPJ, cnpj);
		}
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

	private Map<String, Object> cep(ProviderConfig config, Map<String, Object> params) {
		String cep = BrazilianDocuments.normalize(String.valueOf(params.get(CEP)));
		if (cep.length() != 8) {
			throw new ProviderException("INVALID_INPUT", "cep is not valid", false);
		}
		Map<String, Object> body = get(config, "/cep/v2/{cep}", cep);
		if (body == null) {
			return notFound(CEP, cep);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("found", true);
		out.put("cep", cep);
		out.put("uf", body.get("state"));
		out.put("cidade", body.get("city"));
		out.put("bairro", body.get("neighborhood"));
		out.put("logradouro", body.get("street"));
		return out;
	}

	/** The JSON body, or null on 404 (an answer, not an error). */
	private Map<String, Object> get(ProviderConfig config, String path, String id) {
		Map<String, Object> body;
		try {
			body = clients.apply(config).get().uri(path, id).retrieve().body(new ParameterizedTypeReference<>() {
			});
		}
		catch (RestClientResponseException e) {
			if (e.getStatusCode().value() == 404) {
				return null;
			}
			throw failure(e.getStatusCode(), e);
		}
		catch (ResourceAccessException e) {
			throw new ProviderException("PROVIDER_UNAVAILABLE", "brasilapi unreachable or timed out", true, e);
		}
		if (body == null) {
			throw new ProviderException("PROVIDER_BAD_RESPONSE", "brasilapi returned an empty body", true);
		}
		return body;
	}

	private static Map<String, Object> notFound(String field, String id) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("found", false);
		out.put(field, id);
		return out;
	}

	private static ProviderException failure(HttpStatusCode status, Exception cause) {
		int code = status.value();
		if (code == 429) {
			return new ProviderException("PROVIDER_RATE_LIMITED", "brasilapi rate limit reached", true, cause);
		}
		if (status.is5xxServerError()) {
			return new ProviderException("PROVIDER_UNAVAILABLE", "brasilapi answered " + code, true, cause);
		}
		return new ProviderException("PROVIDER_REJECTED", "brasilapi rejected the request with " + code, false, cause);
	}

}
