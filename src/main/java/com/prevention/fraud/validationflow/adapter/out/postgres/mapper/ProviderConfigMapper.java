package com.prevention.fraud.validationflow.adapter.out.postgres.mapper;

import com.prevention.fraud.validationflow.adapter.out.postgres.entity.ProviderConfigEntity;
import com.prevention.fraud.validationflow.application.provider.ProviderConfig;

public final class ProviderConfigMapper {

	private ProviderConfigMapper() {
	}

	public static ProviderConfig toDomain(ProviderConfigEntity e) {
		return new ProviderConfig(e.id.tenantId(), e.id.providerKey(), e.enabled, e.baseUrl, e.connectTimeoutMs,
				e.readTimeoutMs, e.credentialsRef, e.callbackSecretRef, e.updatedBy, e.updatedAt);
	}

	public static ProviderConfigEntity toEntity(ProviderConfig c) {
		var e = new ProviderConfigEntity();
		e.id = new ProviderConfigEntity.Key(c.tenantId(), c.providerKey());
		e.enabled = c.enabled();
		e.baseUrl = c.baseUrl();
		e.connectTimeoutMs = c.connectTimeoutMs();
		e.readTimeoutMs = c.readTimeoutMs();
		e.credentialsRef = c.credentialsRef();
		e.callbackSecretRef = c.callbackSecretRef();
		e.updatedBy = c.updatedBy();
		e.updatedAt = c.updatedAt();
		return e;
	}

}
