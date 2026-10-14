package com.prevention.fraud.validationflow.application.provider;

import java.time.Instant;

/** Per-tenant provider settings; {@code credentialsRef} names a secret, it never holds one. Null columns mean "use the default". */
public record ProviderConfig(String tenantId, String providerKey, boolean enabled, String baseUrl,
		Integer connectTimeoutMs, Integer readTimeoutMs, String credentialsRef, String callbackSecretRef, String updatedBy,
		Instant updatedAt) {

	/** Without a callback secret: callbacks are then authenticated by the call token alone. */
	public ProviderConfig(String tenantId, String providerKey, boolean enabled, String baseUrl, Integer connectTimeoutMs,
			Integer readTimeoutMs, String credentialsRef, String updatedBy, Instant updatedAt) {
		this(tenantId, providerKey, enabled, baseUrl, connectTimeoutMs, readTimeoutMs, credentialsRef, null, updatedBy,
				updatedAt);
	}

}
