package com.prevention.fraud.validationflow.config;

import java.time.Clock;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.prevention.fraud.validationflow.application.execution.ports.ExecutionRepository;
import com.prevention.fraud.validationflow.application.execution.recovery.WaitExpiryService;

@Configuration
@EnableScheduling
class WaitExpiryConfig {

	private final WaitExpiryService service;

	WaitExpiryConfig(ExecutionRepository repository) {
		this.service = new WaitExpiryService(repository, Clock.systemUTC());
	}

	@Scheduled(fixedDelayString = "${app.wait.expiry-interval:PT1M}")
	void run() {
		service.expire();
	}

}
