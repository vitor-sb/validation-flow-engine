package com.prevention.fraud.validationflow.application;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogMaskerTest {

	@Test
	void masksSensitiveKeysRecursively() {
		Object m = LogMasker.mask(Map.of("cpf", "123", "ok", true,
				"nested", List.of(Map.of("accessToken", "abc", "n", 1))));
		assertEquals(Map.of("cpf", "***", "ok", true, "nested", List.of(Map.of("accessToken", "***", "n", 1))), m);
	}

}
