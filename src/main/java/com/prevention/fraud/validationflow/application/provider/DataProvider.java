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
	Map<String, Object> fetch(Map<String, Object> params);

}
