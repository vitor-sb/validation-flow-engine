package com.prevention.fraud.validationflow.application.provider;

import java.util.List;
import java.util.Optional;

/** Port; every operation is scoped by tenant. */
public interface ProviderConfigRepository {

	Optional<ProviderConfig> find(String tenantId, String providerKey);

	List<ProviderConfig> findAll(String tenantId);

	/** Creates or replaces the row. */
	void save(ProviderConfig config);

}
