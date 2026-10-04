package com.prevention.fraud.validationflow.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.prevention.fraud.validationflow.application.ExecutionRepository;
import com.prevention.fraud.validationflow.application.ExecutionService;
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
	ExecutionService executionService(FlowService flows, ExecutionRepository repository, ValidatorRegistry registry,
			io.micrometer.core.instrument.MeterRegistry meters,
			// must exceed the longest possible execution, or a live run could be taken over
			@org.springframework.beans.factory.annotation.Value("${app.idempotency.lease:PT1H}") java.time.Duration lease) {
		return new ExecutionService(flows, repository, registry, meters, lease);
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
