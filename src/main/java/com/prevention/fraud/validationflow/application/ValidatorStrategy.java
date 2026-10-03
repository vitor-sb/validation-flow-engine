package com.prevention.fraud.validationflow.application;

import java.util.Map;
import java.util.Set;

/** A pluggable validation step, discovered as a Spring bean and addressed by its stable {@link #key()}. */
public interface ValidatorStrategy {

	record ValidationInput(Map<String, Object> data, Map<String, Object> config) {
	}

	record ValidationResult(boolean success, Map<String, Object> output) {
	}

	String key();

	ValidationResult execute(ValidationInput input);

	/** Describes the validator's {@code config} (free-form schema). */
	default Map<String, Object> schema() {
		return Map.of();
	}

	default Set<String> capabilities() {
		return Set.of();
	}

}
