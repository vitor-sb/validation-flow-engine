package com.prevention.fraud.validationflow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.prevention.fraud.validationflow.application.FlowRepository;
import com.prevention.fraud.validationflow.application.FlowService;
import com.prevention.fraud.validationflow.domain.GraphValidator;

@Configuration
class FlowConfig {

	@Bean
	FlowService flowService(FlowRepository repository, GraphValidator graphValidator) {
		return new FlowService(repository, graphValidator);
	}

	// ponytail: no validator registry yet (US-010), so no validatorType is known; the registry replaces this predicate
	@Bean
	GraphValidator graphValidator() {
		return new GraphValidator(type -> false);
	}

}
