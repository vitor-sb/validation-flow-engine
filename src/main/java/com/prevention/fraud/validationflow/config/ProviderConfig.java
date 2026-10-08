package com.prevention.fraud.validationflow.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.prevention.fraud.validationflow.adapter.out.provider.brasilapi.BrasilApiCnpjProvider;
import com.prevention.fraud.validationflow.application.validator.ProviderBackedValidator;
import com.prevention.fraud.validationflow.application.validator.ValidatorStrategy;

/** Wires external data providers as validators. Each one is opt-in, so a default deployment makes no outbound calls. */
@Configuration
class ProviderConfig {

	@Bean
	@ConditionalOnProperty(name = "app.providers.brasilapi.enabled", havingValue = "true")
	BrasilApiCnpjProvider brasilApiCnpjProvider(
			@Value("${app.providers.brasilapi.base-url:https://brasilapi.com.br/api}") String baseUrl,
			// real socket timeouts so a cancelled attempt does not keep its thread; keep read below the node timeout
			@Value("${app.providers.brasilapi.connect-timeout:PT2S}") Duration connectTimeout,
			@Value("${app.providers.brasilapi.read-timeout:PT5S}") Duration readTimeout) {
		var factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(connectTimeout);
		factory.setReadTimeout(readTimeout);
		return new BrasilApiCnpjProvider(RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build());
	}

	@Bean
	@ConditionalOnProperty(name = "app.providers.brasilapi.enabled", havingValue = "true")
	ValidatorStrategy brasilApiCnpjValidator(BrasilApiCnpjProvider provider) {
		return new ProviderBackedValidator("brasilapi-cnpj", provider);
	}

}
