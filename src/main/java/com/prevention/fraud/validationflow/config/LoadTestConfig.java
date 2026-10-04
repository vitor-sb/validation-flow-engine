package com.prevention.fraud.validationflow.config;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.prevention.fraud.validationflow.application.ValidatorStrategy;

/** Fake validator for loadtest/ only; never active without the {@code loadtest} profile. */
@Configuration
@Profile("loadtest")
class LoadTestConfig {

	@Bean
	ValidatorStrategy loadTestFakeValidator() {
		return new ValidatorStrategy() {
			public String key() {
				return "loadtest-fake";
			}

			public ValidationResult execute(ValidationInput input) {
				return new ValidationResult(true, Map.of("fake", true));
			}
		};
	}

}
