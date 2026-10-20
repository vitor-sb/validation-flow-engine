package com.prevention.fraud.validationflow.domain.flow;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Evaluates structured conditions ({@code {operator, field, value}} or {@code {operator: AND|OR|NOT, conditions}})
 * against a data map. No textual expressions. Missing/null/incompatible values are "unknown" (three-valued logic) and
 * the final result is {@code false} for unknown, so a failed lookup is never silently {@code true} (also under NOT).
 */
public final class ConditionEvaluator {

	/** {@code observed}: field value for a comparison, list of child observed values for AND/OR/NOT. */
	public record Evaluation(boolean result, Object observed) {
	}

	private record Node(Boolean result, Object observed) {
	}

	private ConditionEvaluator() {
	}

	public static Evaluation evaluate(Map<String, Object> condition, Map<String, Object> data) {
		Node n = eval(condition, data);
		return new Evaluation(Boolean.TRUE.equals(n.result), n.observed);
	}

	private static Node eval(Map<?, ?> c, Map<String, Object> data) {
		String op = String.valueOf(c.get("operator"));
		switch (op) {
			case "AND", "OR", "NOT" -> {
				List<Object> observed = new ArrayList<>();
				List<Boolean> rs = new ArrayList<>();
				for (Object sub : (List<?>) c.get("conditions")) {
					Node n = eval((Map<?, ?>) sub, data);
					observed.add(n.observed);
					rs.add(n.result);
				}
				Boolean r = switch (op) { // Kleene logic, null = unknown
					case "AND" -> rs.contains(false) ? Boolean.FALSE : rs.contains(null) ? null : Boolean.TRUE;
					case "OR" -> rs.contains(true) ? Boolean.TRUE : rs.contains(null) ? null : Boolean.FALSE;
					default -> rs.get(0) == null ? null : (Boolean) !rs.get(0);
				};
				return new Node(r, observed);
			}
			default -> {
				Object actual = lookup(data, String.valueOf(c.get("field")));
				return new Node(compare(op, actual, c.get("value")), actual);
			}
		}
	}

	/** null = unknown (absent, null or incompatible types). */
	private static Boolean compare(String op, Object actual, Object expected) {
		if (op.equals("EXISTS")) {
			return actual != null;
		}
		if (actual == null) {
			return null;
		}
		switch (op) {
			case "CONTAINS" -> {
				return actual instanceof Collection<?> col ? col.stream().anyMatch(e -> same(e, expected)) : null;
			}
			case "IN" -> {
				return expected instanceof Collection<?> col ? col.stream().anyMatch(e -> same(e, actual)) : null;
			}
			case "EQUALS", "NOT_EQUALS" -> {
				if (expected == null || !comparable(actual, expected)) {
					return null;
				}
				return same(actual, expected) == op.equals("EQUALS");
			}
			default -> {
				if (expected == null || !comparable(actual, expected)) {
					return null;
				}
				int cmp = order(actual, expected);
				return switch (op) {
					case "GREATER_THAN" -> cmp > 0;
					case "GREATER_THAN_OR_EQUALS" -> cmp >= 0;
					case "LESS_THAN" -> cmp < 0;
					case "LESS_THAN_OR_EQUALS" -> cmp <= 0;
					default -> null;
				};
			}
		}
	}

	private static boolean comparable(Object a, Object b) {
		return (a instanceof Number && b instanceof Number) || (a instanceof String && b instanceof String)
				|| (a instanceof Boolean && b instanceof Boolean);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static int order(Object a, Object b) {
		if (a instanceof Number x && b instanceof Number y) {
			return dec(x).compareTo(dec(y));
		}
		return a instanceof Comparable ca ? ca.compareTo(b) : 0;
	}

	private static boolean same(Object a, Object b) {
		if (a instanceof Number x && b instanceof Number y) {
			return dec(x).compareTo(dec(y)) == 0;
		}
		return a != null && a.equals(b);
	}

	private static BigDecimal dec(Number n) {
		return new BigDecimal(n.toString());
	}

	/** Dotted-path lookup ({@code a.b.c}); null when any step is missing. */
	public static Object lookup(Map<String, Object> data, String path) {
		Object cur = data;
		for (String part : path.split("\\.")) {
			if (!(cur instanceof Map<?, ?> m)) {
				return null;
			}
			cur = m.get(part);
		}
		return cur;
	}
}
