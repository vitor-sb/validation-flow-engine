package com.prevention.fraud.validationflow.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.prevention.fraud.validationflow.application.execution.ports.ExecutionRepository;
import com.prevention.fraud.validationflow.application.execution.ExecutionService;
import com.prevention.fraud.validationflow.application.execution.IdempotentExecutionService;
import com.prevention.fraud.validationflow.application.execution.ValidatorRunner;
import com.prevention.fraud.validationflow.application.flow.ports.FlowRepository;
import com.prevention.fraud.validationflow.application.flow.FlowService;
import com.prevention.fraud.validationflow.application.validator.ValidatorRegistry;
import com.prevention.fraud.validationflow.application.validator.ValidatorStrategy;
import com.prevention.fraud.validationflow.domain.flow.GraphValidator;

@Configuration
class FlowConfig {

	@Bean
	FlowService flowService(FlowRepository repository, GraphValidator graphValidator) {
		return new FlowService(repository, graphValidator);
	}

	@Bean
	ExecutionService executionService(FlowService flows, ExecutionRepository repository, ValidatorRegistry registry,
			io.micrometer.core.instrument.MeterRegistry meters,
			// caps threads stuck in validators that ignore interrupt; size to expected concurrent timed validators
			@org.springframework.beans.factory.annotation.Value("${app.validators.max-threads:64}") int maxThreads,
			@org.springframework.beans.factory.annotation.Value("${app.validators.queue-capacity:128}") int queueCapacity) {
		return new ExecutionService(flows, repository, registry, new ValidatorRunner(new MicrometerExecutionMetrics(meters), maxThreads, queueCapacity));
	}

	@Bean
	IdempotentExecutionService idempotentExecutionService(ExecutionService executions, ExecutionRepository repository,
			// must exceed the longest possible execution, or a live run could be taken over
			@org.springframework.beans.factory.annotation.Value("${app.idempotency.lease:PT1H}") java.time.Duration lease) {
		return new IdempotentExecutionService(executions, repository, lease);
	}

	@Bean
	ValidatorRegistry validatorRegistry(ObjectProvider<ValidatorStrategy> strategies) {
		return new ValidatorRegistry(strategies.orderedStream().toList());
	}

	@Bean
	GraphValidator graphValidator(ValidatorRegistry registry,
			@org.springframework.beans.factory.annotation.Value("${app.limits.max-nodes:" + GraphValidator.DEFAULT_MAX_NODES + "}") int maxNodes,
			@org.springframework.beans.factory.annotation.Value("${app.limits.max-transitions:" + GraphValidator.DEFAULT_MAX_TRANSITIONS + "}") int maxTransitions,
			@org.springframework.beans.factory.annotation.Value("${app.limits.max-attempts:" + GraphValidator.DEFAULT_MAX_ATTEMPTS + "}") int maxAttempts,
			@org.springframework.beans.factory.annotation.Value("${app.limits.max-delay:PT1M}") java.time.Duration maxDelay,
			@org.springframework.beans.factory.annotation.Value("${app.limits.max-timeout:PT5M}") java.time.Duration maxTimeout) {
		return new GraphValidator(registry::contains, maxNodes, maxTransitions, maxAttempts, maxDelay, maxTimeout);
	}

}
