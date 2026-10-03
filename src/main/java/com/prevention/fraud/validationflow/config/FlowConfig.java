package com.prevention.fraud.validationflow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.prevention.fraud.validationflow.application.FlowRepository;
import com.prevention.fraud.validationflow.application.FlowService;

@Configuration
class FlowConfig {

	@Bean
	FlowService flowService(FlowRepository repository) {
		return new FlowService(repository);
	}

}
