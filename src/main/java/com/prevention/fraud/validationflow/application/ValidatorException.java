package com.prevention.fraud.validationflow.application;

/** Thrown by a {@link ValidatorStrategy} to report a failure; only {@code retryable} ones are retried. */
public class ValidatorException extends RuntimeException {

	private final String code;

	private final boolean retryable;

	public ValidatorException(String code, String message, boolean retryable) {
		super(message);
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
