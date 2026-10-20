package com.prevention.fraud.validationflow.application.provider;

/** Turns a stored credentials reference into the secret. The reference is never the secret itself. */
public interface CredentialResolver {

	/** @throws ProviderException {@code PROVIDER_MISCONFIGURED} (not retryable) when the reference cannot be resolved */
	String resolve(String ref);

}
