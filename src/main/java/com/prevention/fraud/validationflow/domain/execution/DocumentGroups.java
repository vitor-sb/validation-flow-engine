package com.prevention.fraud.validationflow.domain.execution;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Document groups declared in a node's {@code config.params.documentGroups} (so they live in the flow snapshot, no global
 * registry). Group: {@code {name, condition?, items[]}}; item: {@code {document}} (required), {@code {oneOf:[..]}}
 * (alternatives, at least 2) and an optional {@code condition} on any item. A group without condition always applies.
 */
public final class DocumentGroups {

	private DocumentGroups() {
	}

	/** True when {@code groups} has a valid shape (conditions are checked with {@code validCondition}). */
	public static boolean valid(Object groups, java.util.function.Predicate<Object> validCondition) {
		if (!(groups instanceof List<?> gs) || gs.isEmpty()) {
			return false;
		}
		Set<Object> names = new HashSet<>();
		for (Object g : gs) {
			if (!(g instanceof Map<?, ?> m) || !(m.get("name") instanceof String n) || n.isBlank() || !names.add(n)
					|| (m.containsKey("condition") && !validCondition.test(m.get("condition")))
					|| !(m.get("items") instanceof List<?> items) || items.isEmpty()) {
				return false;
			}
			for (Object i : (List<?>) m.get("items")) {
				if (!(i instanceof Map<?, ?> item) || (item.containsKey("condition") && !validCondition.test(item.get("condition")))
						|| item.containsKey("document") == item.containsKey("oneOf")
						|| (item.containsKey("document") && !(item.get("document") instanceof String d && !d.isBlank()))
						|| (item.containsKey("oneOf") && !(item.get("oneOf") instanceof List<?> o && o.size() >= 2
								&& o.stream().allMatch(x -> x instanceof String s && !s.isBlank())))) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Evaluates every group against {@code ctx}. Output: {@code groups[{name, selected, observed}]} for all groups and
	 * {@code documents[{group, kind: REQUIRED|ONE_OF, document | options}]} for what was effectively applied.
	 */
	@SuppressWarnings("unchecked")
	public static Map<String, Object> resolve(List<Map<String, Object>> groups, Map<String, Object> ctx) {
		List<Map<String, Object>> evaluated = new ArrayList<>();
		List<Map<String, Object>> documents = new ArrayList<>();
		for (Map<String, Object> g : groups) {
			var ev = g.get("condition") == null ? null : ConditionEvaluator.evaluate((Map<String, Object>) g.get("condition"), ctx);
			boolean selected = ev == null || ev.result();
			evaluated.add(Map.of("name", g.get("name"), "selected", selected, "observed", String.valueOf(ev == null ? null : ev.observed())));
			if (!selected) {
				continue;
			}
			for (Map<String, Object> item : (List<Map<String, Object>>) g.get("items")) {
				if (item.get("condition") != null && !ConditionEvaluator.evaluate((Map<String, Object>) item.get("condition"), ctx).result()) {
					continue;
				}
				Map<String, Object> d = new LinkedHashMap<>();
				d.put("group", g.get("name"));
				d.put("kind", item.containsKey("oneOf") ? "ONE_OF" : "REQUIRED");
				d.put(item.containsKey("oneOf") ? "options" : "document", item.containsKey("oneOf") ? item.get("oneOf") : item.get("document"));
				documents.add(d);
			}
		}
		return Map.of("groups", evaluated, "documents", documents);
	}

}
