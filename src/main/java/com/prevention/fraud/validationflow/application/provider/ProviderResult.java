package com.prevention.fraud.validationflow.application.provider;

import java.time.Duration;
import java.util.Map;

/** Outcome of {@link DataProvider#start} / {@link DataProvider#poll}: the data now, or a call still in flight. */
public sealed interface ProviderResult {

	enum Mode { CALLBACK, POLL }

	/** Normalized data, same canonical shape as {@link DataProvider#fetch}. */
	record Done(Map<String, Object> data) implements ProviderResult {
	}

	/** {@code externalRef} is the provider's id for the call; {@code pollAfter} is the first poll delay (POLL mode). */
	record Pending(String externalRef, Mode mode, Duration pollAfter) implements ProviderResult {
	}

}
