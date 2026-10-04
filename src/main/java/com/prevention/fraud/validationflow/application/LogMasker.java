package com.prevention.fraud.validationflow.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Masks sensitive values (documents, tokens, secrets) before they reach technical logs. */
final class LogMasker {

	private static final Pattern SENSITIVE = Pattern
			.compile("(?i).*(document|cpf|cnpj|token|password|secret|authorization|api[-_]?key).*");

	private LogMasker() {
	}

	@SuppressWarnings("unchecked")
	static Object mask(Object value) {
		if (value instanceof Map<?, ?> m) {
			Map<String, Object> out = new LinkedHashMap<>();
			m.forEach((k, v) -> out.put(String.valueOf(k), SENSITIVE.matcher(String.valueOf(k)).matches() ? "***" : mask(v)));
			return out;
		}
		if (value instanceof List<?> l) {
			return l.stream().map(LogMasker::mask).toList();
		}
		return value;
	}

}
