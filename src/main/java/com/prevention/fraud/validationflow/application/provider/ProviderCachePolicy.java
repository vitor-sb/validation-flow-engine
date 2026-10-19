package com.prevention.fraud.validationflow.application.provider;

import java.time.LocalDate;
import java.util.List;

/** Validity rule for (tenant, provider, operation); without one nothing is cached. Null keyFields = all parameters. */
public record ProviderCachePolicy(String tenantId, String providerKey, String operation, String dataCategory,
		Integer ttlSeconds, boolean fallbackOnErrorAllowed, String justification, String owner, LocalDate reviewDate,
		List<String> keyFields) {
}
