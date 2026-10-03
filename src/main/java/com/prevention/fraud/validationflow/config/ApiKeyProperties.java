package com.prevention.fraud.validationflow.config;

import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Static API keys; each one is bound to a single tenant and a set of scopes (e.g. {@code flow:write}). */
@ConfigurationProperties("app.security")
record ApiKeyProperties(List<ApiKey> apiKeys) {

	ApiKeyProperties {
		apiKeys = apiKeys == null ? List.of() : apiKeys;
	}

	record ApiKey(String key, String tenantId, Set<String> scopes) {
	}

}
