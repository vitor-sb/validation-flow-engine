package com.prevention.fraud.validationflow.domain.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConditionEvaluatorTest {

	private static final Map<String, Object> DATA = Map.of("age", 30, "name", "ana", "groups", List.of("A", "B"),
			"doc", Map.of("type", "CPF"), "nothing", Map.of());

	private static Map<String, Object> c(String op, String field, Object value) {
		return Map.of("operator", op, "field", field, "value", value);
	}

	private static boolean ok(Map<String, Object> cond) {
		return ConditionEvaluator.evaluate(cond, DATA).result();
	}

	@Test
	void comparisons() {
		assertThat(ok(c("EQUALS", "age", 30.0))).isTrue();
		assertThat(ok(c("EQUALS", "doc.type", "CPF"))).isTrue();
		assertThat(ok(c("NOT_EQUALS", "name", "bob"))).isTrue();
		assertThat(ok(c("GREATER_THAN", "age", 29))).isTrue();
		assertThat(ok(c("GREATER_THAN_OR_EQUALS", "age", 30))).isTrue();
		assertThat(ok(c("LESS_THAN", "age", 31))).isTrue();
		assertThat(ok(c("LESS_THAN_OR_EQUALS", "age", 30))).isTrue();
		assertThat(ok(c("CONTAINS", "groups", "A"))).isTrue();
		assertThat(ok(c("IN", "name", List.of("ana", "bob")))).isTrue();
		assertThat(ok(Map.of("operator", "EXISTS", "field", "age"))).isTrue();
	}

	@Test
	void missingNullOrIncompatibleIsFalsePerOperator() {
		for (String op : List.of("EQUALS", "NOT_EQUALS", "GREATER_THAN", "GREATER_THAN_OR_EQUALS", "LESS_THAN",
				"LESS_THAN_OR_EQUALS", "CONTAINS")) {
			assertThat(ok(c(op, "missing", 1))).as(op + " missing").isFalse();
			assertThat(ok(c(op, "name", 1))).as(op + " incompatible").isFalse();
		}
		assertThat(ok(c("IN", "missing", List.of(1)))).isFalse();
		assertThat(ok(c("IN", "age", "not-a-list"))).isFalse();
		assertThat(ok(c("CONTAINS", "name", "a"))).as("CONTAINS needs a collection").isFalse();
		assertThat(ok(Map.of("operator", "EXISTS", "field", "missing"))).isFalse();
	}

	@Test
	void notOfUnknownIsNeverSilentlyTrue() {
		assertThat(ok(Map.of("operator", "NOT", "conditions", List.of(c("EQUALS", "missing", 1))))).isFalse();
		assertThat(ok(Map.of("operator", "NOT", "conditions", List.of(c("EQUALS", "age", 1))))).isTrue();
	}

	@Test
	void andOr() {
		var yes = c("EQUALS", "age", 30);
		var no = c("EQUALS", "age", 1);
		assertThat(ok(Map.of("operator", "AND", "conditions", List.of(yes, yes)))).isTrue();
		assertThat(ok(Map.of("operator", "AND", "conditions", List.of(yes, no)))).isFalse();
		assertThat(ok(Map.of("operator", "OR", "conditions", List.of(no, yes)))).isTrue();
		assertThat(ok(Map.of("operator", "OR", "conditions", List.of(no, no)))).isFalse();
	}

	@Test
	void returnsObservedValue() {
		assertThat(ConditionEvaluator.evaluate(c("EQUALS", "age", 1), DATA).observed()).isEqualTo(30);
		assertThat(ConditionEvaluator.evaluate(c("EQUALS", "missing", 1), DATA).observed()).isNull();
	}
}
