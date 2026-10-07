package com.prevention.fraud.validationflow.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.prevention.fraud.validationflow.application.ExecutionRepository;
import com.prevention.fraud.validationflow.application.RecoveryService;

/** Off unless `app.recovery.stale-after` is set; it must exceed the longest possible execution. */
@Configuration
@EnableScheduling
@ConditionalOnProperty("app.recovery.stale-after")
class RecoveryConfig {

	private final RecoveryService service;

	RecoveryConfig(ExecutionRepository repository, @Value("${app.recovery.stale-after}") Duration staleAfter) {
		this.service = new RecoveryService(repository, staleAfter);
	}

	@Scheduled(fixedDelayString = "${app.recovery.interval:PT1M}")
	void run() {
		service.recover();
	}

}
