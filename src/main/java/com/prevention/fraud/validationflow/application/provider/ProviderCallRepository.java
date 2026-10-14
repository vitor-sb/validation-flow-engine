package com.prevention.fraud.validationflow.application.provider;

import java.util.Optional;
import java.util.UUID;

/** Port; every operation is scoped by tenant. */
public interface ProviderCallRepository {

	/** Creates or replaces the row. */
	void save(ProviderCall call);

	/** Callback lookup: the caller has no tenant yet, the call's own token authenticates it. */
	Optional<ProviderCall> findById(UUID id);

	Optional<ProviderCall> find(String tenantId, UUID id);

}
