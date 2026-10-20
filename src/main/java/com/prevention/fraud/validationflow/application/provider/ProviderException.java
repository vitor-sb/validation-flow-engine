package com.prevention.fraud.validationflow.application.provider;

/**
 * Failure of an external provider. The message is persisted and logged as is, so it MUST NOT contain credentials,
 * request parameters or response bodies.
 */
public class ProviderException extends RuntimeException {

	private final String code;

	private final boolean retryable;

	public ProviderException(String code, String message, boolean retryable) {
		super(message);
		this.code = code;
		this.retryable = retryable;
	}

	public ProviderException(String code, String message, boolean retryable, Throwable cause) {
		super(message, cause);
		this.code = code;
		this.retryable = retryable;
	}

	public String code() {
		return code;
	}

	public boolean retryable() {
		return retryable;
	}

}
