package com.prevention.fraud.validationflow.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Masks sensitive values. {@link #mask} is the log policy (documents + credentials); {@link #maskSecrets} is the
 * persistence policy (credentials only: CPF/CNPJ stay intact in the audit record).
 */
final class LogMasker {

	private static final String SECRETS = "token|password|secret|authorization|api[-_]?key";

	private static final Pattern SENSITIVE = Pattern.compile("(?i).*(document|cpf|cnpj|" + SECRETS + ").*");

	private static final Pattern CREDENTIAL = Pattern.compile("(?i).*(" + SECRETS + ").*");

	private LogMasker() {
	}

	@SuppressWarnings("unchecked")
	static Object mask(Object value) {
		return mask(value, SENSITIVE);
	}

	/** Credentials only; safe for audit persistence. */
	static Object maskSecrets(Object value) {
		return mask(value, CREDENTIAL);
	}

	private static Object mask(Object value, Pattern sensitive) {
		if (value instanceof Map<?, ?> m) {
			Map<String, Object> out = new LinkedHashMap<>();
			m.forEach((k, v) -> out.put(String.valueOf(k), sensitive.matcher(String.valueOf(k)).matches() ? "***" : mask(v, sensitive)));
			return out;
		}
		if (value instanceof List<?> l) {
			return l.stream().map(e -> mask(e, sensitive)).toList();
		}
		return value;
	}

}
