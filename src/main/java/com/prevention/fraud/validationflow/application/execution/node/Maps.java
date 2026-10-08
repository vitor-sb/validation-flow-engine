package com.prevention.fraud.validationflow.application.execution.node;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Type-safe views over the untyped JSON trees (snapshot, context): copies instead of unchecked casts. */
public final class Maps {

	private Maps() {
	}

	/** Copy of {@code o} as a String-keyed map; empty when {@code o} is not a map. */
	public static Map<String, Object> of(Object o) {
		Map<String, Object> out = new LinkedHashMap<>();
		if (o instanceof Map<?, ?> m) {
			m.forEach((k, v) -> out.put(String.valueOf(k), v));
		}
		return out;
	}

	/** Copy of {@code o} as a list of maps; empty when {@code o} is not a list. */
	public static List<Map<String, Object>> list(Object o) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (o instanceof List<?> l) {
			l.forEach(e -> out.add(of(e)));
		}
		return out;
	}

	/** Copy of {@code o} as a String-to-String map (values stringified); empty when {@code o} is not a map. */
	public static Map<String, String> strings(Object o) {
		Map<String, String> out = new LinkedHashMap<>();
		of(o).forEach((k, v) -> out.put(k, String.valueOf(v)));
		return out;
	}

}
