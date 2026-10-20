package com.prevention.fraud.validationflow.application.execution.node;

import com.prevention.fraud.validationflow.application.execution.node.ValidatorRunner;

import static org.assertj.core.api.Assertions.assertThat;

import com.prevention.fraud.validationflow.domain.flow.GraphValidator;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ValidatorRunnerTest {

	private static final Duration DELAY = Duration.ofMillis(100);

	@Test
	void fixedBackoffRepeatsTheDelay() {
		assertThat(ValidatorRunner.backoffMillis(DELAY, false, 1)).isEqualTo(100);
		assertThat(ValidatorRunner.backoffMillis(DELAY, false, 5)).isEqualTo(100);
	}

	@Test
	void exponentialBackoffDoublesEachAttempt() {
		assertThat(ValidatorRunner.backoffMillis(DELAY, true, 1)).isEqualTo(100);
		assertThat(ValidatorRunner.backoffMillis(DELAY, true, 2)).isEqualTo(200);
		assertThat(ValidatorRunner.backoffMillis(DELAY, true, 4)).isEqualTo(800);
	}

	@Test
	void exponentialBackoffIsCappedAndNeverOverflows() {
		long cap = GraphValidator.DEFAULT_MAX_DELAY.toMillis();
		assertThat(ValidatorRunner.backoffMillis(DELAY, true, 30)).isEqualTo(cap);
		assertThat(ValidatorRunner.backoffMillis(DELAY, true, Integer.MAX_VALUE)).isEqualTo(cap);
	}

}
