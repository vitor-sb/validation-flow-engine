package com.prevention.fraud.validationflow.application.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** SHA-256 of a canonical rendering (map keys sorted) so key order in a payload doesn't matter. */
public final class CanonicalHash {

	private CanonicalHash() {
	}

	public static String sha256(Object payload) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(canonical(payload).getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String canonical(Object o) {
		if (o instanceof Map<?, ?> m) {
			var sb = new StringBuilder("{");
			new TreeMap<String, Object>(m.entrySet().stream()
					.collect(java.util.stream.Collectors.toMap(e -> String.valueOf(e.getKey()), Map.Entry::getValue)))
					.forEach((k, v) -> sb.append(k.length()).append(':').append(k).append('=').append(canonical(v)).append(','));
			return sb.append('}').toString();
		}
		if (o instanceof Iterable<?> it) {
			var sb = new StringBuilder("[");
			it.forEach(v -> sb.append(canonical(v)).append(','));
			return sb.append(']').toString();
		}
		return o == null ? "null" : o.getClass().getSimpleName() + ":" + o;
	}

}
