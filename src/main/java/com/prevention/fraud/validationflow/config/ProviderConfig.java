package com.prevention.fraud.validationflow.config;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.prevention.fraud.validationflow.adapter.out.credential.EnvCredentialResolver;
import com.prevention.fraud.validationflow.adapter.out.provider.brasilapi.BrasilApiCnpjProvider;
import com.prevention.fraud.validationflow.application.provider.CredentialResolver;
import com.prevention.fraud.validationflow.application.provider.ProviderConfigRepository;
import com.prevention.fraud.validationflow.application.provider.ProviderConfigService;
import com.prevention.fraud.validationflow.application.validator.ProviderBackedValidator;
import com.prevention.fraud.validationflow.application.validator.ValidatorStrategy;

/** Wires external data providers as validators. Each is enabled per tenant in {@code provider_config}; no row means no outbound call. */
@Configuration
class ProviderConfig {

	private static final String BRASILAPI_URL = "https://brasilapi.com.br/api";

	@Bean
	ProviderConfigService providerConfigService(ProviderConfigRepository repository,
			@Value("${app.providers.config-cache-ttl:PT30S}") Duration ttl) {
		return new ProviderConfigService(repository, ttl, Clock.systemUTC());
	}

	@Bean
	CredentialResolver credentialResolver() {
		return new EnvCredentialResolver();
	}

	@Bean
	BrasilApiCnpjProvider brasilApiCnpjProvider() {
		return new BrasilApiCnpjProvider(clients(BRASILAPI_URL, 2000, 5000));
	}

	@Bean
	ValidatorStrategy brasilApiCnpjValidator(BrasilApiCnpjProvider provider, ProviderConfigService configs,
			com.prevention.fraud.validationflow.application.provider.ProviderCache cache) {
		return new ProviderBackedValidator("brasilapi-cnpj", provider, configs, cache);
	}

	/**
	 * One RestClient per distinct (baseUrl, timeouts): a changed row maps to a new key and so to a rebuilt client.
	 * Real socket timeouts so a cancelled attempt does not keep its thread; keep read below the node timeout.
	 */
	private static Function<com.prevention.fraud.validationflow.application.provider.ProviderConfig, RestClient> clients(
			String defaultUrl, int defaultConnectMs, int defaultReadMs) {
		record Settings(String baseUrl, int connectMs, int readMs) {
		}
		// ponytail: stale entries are never evicted; fine while rows change rarely, add a size cap if they churn
		var cache = new ConcurrentHashMap<Settings, RestClient>();
		return c -> cache.computeIfAbsent(new Settings(c.baseUrl() != null ? c.baseUrl() : defaultUrl,
				c.connectTimeoutMs() != null ? c.connectTimeoutMs() : defaultConnectMs,
				c.readTimeoutMs() != null ? c.readTimeoutMs() : defaultReadMs), k -> {
					var factory = new SimpleClientHttpRequestFactory();
					factory.setConnectTimeout(Duration.ofMillis(k.connectMs()));
					factory.setReadTimeout(Duration.ofMillis(k.readMs()));
					return RestClient.builder().baseUrl(k.baseUrl()).requestFactory(factory).build();
				});
	}

}
