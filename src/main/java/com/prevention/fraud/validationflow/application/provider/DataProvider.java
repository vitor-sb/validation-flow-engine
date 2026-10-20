package com.prevention.fraud.validationflow.application.provider;

import java.util.Map;

/**
 * Port to an external data source (a public registry, a paid bureau). It only fetches and normalizes data; deciding
 * whether the data passes is the job of the validator that wraps it, so providers stay swappable.
 *
 * <p>Timeout and retry belong to the engine ({@code ValidatorRunner}), not to implementations: report failures with
 * {@link ProviderException} and set {@code retryable} accordingly, but do not retry internally. Implementations must
 * still use real connect/read timeouts, otherwise a cancelled attempt keeps its thread.
 */
public interface DataProvider {

	String key();

	/** Returns normalized data. Keep only the fields needed for decisions: the result is persisted in the node output. */
	/** {@code config} is the tenant's enabled row for this provider; null columns mean the adapter's defaults. */
	Map<String, Object> fetch(ProviderConfig config, Map<String, Object> params);

	/** Parameters as they identify a cache entry, so equivalent values (e.g. a formatted and a bare document) share one. Runs before {@code keyFields} is applied. */
	default Map<String, Object> cacheKeyParams(Map<String, Object> params) {
		return params;
	}

	/** Starts a call. Sync providers keep the default; async ones return {@link ProviderResult.Pending}. */
	default ProviderResult start(ProviderConfig config, Map<String, Object> params) {
		return new ProviderResult.Done(fetch(config, params));
	}

	/**
	 * Starts a call whose answer may come back as a callback: {@code callback} carries the id and clear token the provider
	 * must send back. Providers that support callbacks override this; the default ignores the handle.
	 */
	default ProviderResult start(ProviderConfig config, Map<String, Object> params, CallbackHandle callback) {
		return start(config, params);
	}

	/** Normalizes a callback body to the same canonical shape as {@link #fetch}; throws {@link ProviderException} if unusable. */
	default Map<String, Object> normalizeCallback(ProviderConfig config, Map<String, Object> body) {
		throw new UnsupportedOperationException(key() + " does not accept callbacks");
	}

	/** Checks a pending call. Only async providers implement it. */
	default ProviderResult poll(ProviderConfig config, String externalRef) {
		throw new UnsupportedOperationException(key() + " is not asynchronous");
	}

}
