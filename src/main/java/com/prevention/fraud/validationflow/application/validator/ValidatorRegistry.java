package com.prevention.fraud.validationflow.application.validator;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

public class ValidatorRegistry {

	private final Map<String, ValidatorStrategy> byKey = new TreeMap<>();

	public ValidatorRegistry(Collection<ValidatorStrategy> strategies) {
		for (ValidatorStrategy s : strategies) {
			if (byKey.putIfAbsent(s.key(), s) != null) {
				throw new IllegalStateException("Duplicate validator key: " + s.key());
			}
		}
	}

	public Optional<ValidatorStrategy> find(String key) {
		return Optional.ofNullable(byKey.get(key));
	}

	public boolean contains(String key) {
		return byKey.containsKey(key);
	}

	public List<ValidatorStrategy> all() {
		return List.copyOf(byKey.values());
	}

}
