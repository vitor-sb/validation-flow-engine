package com.prevention.fraud.validationflow.application.provider;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** A normalized provider response with an explicit validity; {@code requestKey} is the hash of the parameters, never the clear document. */
public record ProviderCacheEntry(UUID id, String tenantId, String providerKey, String operation, String requestKey,
		Map<String, Object> response, String dataCategory, Instant fetchedAt, Instant validUntil, String policyApplied) {
}
