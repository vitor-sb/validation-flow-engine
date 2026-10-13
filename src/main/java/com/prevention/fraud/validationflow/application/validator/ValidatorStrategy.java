package com.prevention.fraud.validationflow.application.validator;

import java.util.Map;
import java.util.Set;

/**
 * A pluggable validation step, discovered as a Spring bean and addressed by its stable {@link #key()}.
 *
 * <p>Implementations MUST be idempotent: the engine may re-execute them after a timeout or a retryable failure.
 * On timeout the running task is cancelled with {@code Future.cancel(true)} before the retry starts, so
 * implementations should react to thread interruption and stop work promptly.
 *
 * <p>Messages of {@link ValidatorException} are persisted and logged verbatim: they MUST NOT contain credentials or
 * input data. Other exceptions are never persisted with their message.
 */
public interface ValidatorStrategy {

	record ValidationInput(Map<String, Object> data, Map<String, Object> config, String tenantId) {

		public ValidationInput(Map<String, Object> data, Map<String, Object> config) {
			this(data, config, null);
		}

	}

	record ValidationResult(boolean success, Map<String, Object> output) {
	}

	String key();

	ValidationResult execute(ValidationInput input);

	/** Describes the validator's {@code config} (free-form schema). */
	default Map<String, Object> schema() {
		return Map.of();
	}

	/** Key of the {@code DataProvider} behind this validator (matches {@code provider_config.provider_key}); null when none. */
	default String providerKey() {
		return null;
	}

	default Set<String> capabilities() {
		return Set.of();
	}

}
