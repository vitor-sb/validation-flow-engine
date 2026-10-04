package com.prevention.fraud.validationflow.application;

import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

	static boolean isCredential(String key) {
		return CREDENTIAL.matcher(key).matches();
	}

	/** Literal scalar values stored under credential keys (nested too); empty values are ignored. */
	static Set<String> credentialValues(Object value) {
		Set<String> out = new LinkedHashSet<>();
		collect(value, false, out);
		return out;
	}

	private static void collect(Object v, boolean underCredential, Set<String> out) {
		if (v instanceof Map<?, ?> m) {
			m.forEach((k, e) -> collect(e, underCredential || isCredential(String.valueOf(k)), out));
		}
		else if (v instanceof Iterable<?> it) {
			it.forEach(e -> collect(e, underCredential, out));
		}
		else if (underCredential && v != null && !String.valueOf(v).isEmpty()) {
			out.add(String.valueOf(v));
		}
	}

	/** Exact (non-regex) replacement of each known secret by {@code ***} in every string of {@code value}. */
	static Object redact(Object value, Collection<String> secrets) {
		if (value instanceof String s) {
			String r = s;
			for (String secret : secrets.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
				r = r.replace(secret, "***");
			}
			return r;
		}
		if (value instanceof Map<?, ?> m) {
			Map<String, Object> out = new LinkedHashMap<>();
			m.forEach((k, v) -> out.put(String.valueOf(k), redact(v, secrets)));
			return out;
		}
		if (value instanceof List<?> l) {
			return l.stream().map(e -> redact(e, secrets)).toList();
		}
		return value;
	}

}
