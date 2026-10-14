package com.prevention.fraud.validationflow.application.provider;

import java.util.Optional;
import java.util.UUID;

/** Port; every operation is scoped by tenant. */
public interface ProviderCallRepository {

	/** Creates or replaces the row. */
	void save(ProviderCall call);

	Optional<ProviderCall> find(String tenantId, UUID id);

}
