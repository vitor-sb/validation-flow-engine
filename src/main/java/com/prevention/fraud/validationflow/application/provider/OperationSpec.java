package com.prevention.fraud.validationflow.application.provider;

import java.util.Set;

/**
 * One query a provider offers: its key and the parameter names it takes. {@code anyParams} (legacy {@code default}
 * operation only) accepts whatever the node sends.
 */
public record OperationSpec(String key, Set<String> required, Set<String> optional, boolean anyParams) {

	/** Operation of a provider that does not declare its own. */
	public static final String DEFAULT = "default";

	public OperationSpec(String key, Set<String> required, Set<String> optional) {
		this(key, required, optional, false);
	}

	public static OperationSpec legacy() {
		return new OperationSpec(DEFAULT, Set.of(), Set.of(), true);
	}

}
