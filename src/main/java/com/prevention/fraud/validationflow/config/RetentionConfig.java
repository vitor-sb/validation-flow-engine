package com.prevention.fraud.validationflow.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.prevention.fraud.validationflow.application.execution.ports.ExecutionRepository;
import com.prevention.fraud.validationflow.application.execution.IdempotencyRetentionService;

/** Off unless `app.retention.idempotency` is set: how long a replay of an Idempotency-Key is honoured. */
@Configuration
@EnableScheduling
@ConditionalOnProperty("app.retention.idempotency")
class RetentionConfig {

	private final IdempotencyRetentionService service;

	RetentionConfig(ExecutionRepository repository, @Value("${app.retention.idempotency}") Duration retention) {
		this.service = new IdempotencyRetentionService(repository, retention);
	}

	@Scheduled(fixedDelayString = "${app.retention.interval:PT1H}")
	void run() {
		service.purge();
	}

}
