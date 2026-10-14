package com.prevention.fraud.validationflow.config;

import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.prevention.fraud.validationflow.application.provider.ProviderCacheRepository;

/** Off unless `app.retention.provider-cache` (the grace after `valid_until`) is set; stale fallback can use an entry only within it. */
@Configuration
@EnableScheduling
@ConditionalOnProperty("app.retention.provider-cache")
class ProviderCacheRetentionConfig {

	private final ProviderCacheRepository repository;

	private final Duration grace;

	ProviderCacheRetentionConfig(ProviderCacheRepository repository, @Value("${app.retention.provider-cache}") Duration grace) {
		this.repository = repository;
		this.grace = grace;
	}

	@Scheduled(fixedDelayString = "${app.retention.interval:PT1H}")
	void run() {
		repository.deleteEntriesExpiredBefore(Instant.now().minus(grace));
	}

}
