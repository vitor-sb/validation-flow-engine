package com.prevention.fraud.validationflow.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.prevention.fraud.validationflow.application.FlowRepository;
import com.prevention.fraud.validationflow.application.FlowService;
import com.prevention.fraud.validationflow.application.ValidatorRegistry;
import com.prevention.fraud.validationflow.application.ValidatorStrategy;
import com.prevention.fraud.validationflow.domain.GraphValidator;

@Configuration
class FlowConfig {

	@Bean
	FlowService flowService(FlowRepository repository, GraphValidator graphValidator) {
		return new FlowService(repository, graphValidator);
	}

	@Bean
	ValidatorRegistry validatorRegistry(ObjectProvider<ValidatorStrategy> strategies) {
		return new ValidatorRegistry(strategies.orderedStream().toList());
	}

	@Bean
	GraphValidator graphValidator(ValidatorRegistry registry) {
		return new GraphValidator(registry::contains);
	}

}
