package com.prevention.fraud.validationflow.application.provider;

import java.time.LocalDate;

/** Validity rule for (tenant, provider, operation); without one nothing is cached. */
public record ProviderCachePolicy(String tenantId, String providerKey, String operation, String dataCategory,
		Integer ttlSeconds, boolean fallbackOnErrorAllowed, String justification, String owner, LocalDate reviewDate) {
}
