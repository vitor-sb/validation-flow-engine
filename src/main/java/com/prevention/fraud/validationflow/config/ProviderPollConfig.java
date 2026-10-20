package com.prevention.fraud.validationflow.config;

import java.time.Clock;
import java.time.Duration;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.prevention.fraud.validationflow.application.execution.ProviderCallAdminService;
import com.prevention.fraud.validationflow.application.execution.ProviderCallbackService;
import com.prevention.fraud.validationflow.application.execution.ProviderPollService;
import com.prevention.fraud.validationflow.application.provider.DataProvider;
import com.prevention.fraud.validationflow.application.provider.ProviderCallRepository;
import com.prevention.fraud.validationflow.application.provider.ProviderConfigService;
import com.prevention.fraud.validationflow.application.provider.ProviderMetrics;

/** Runs the poll job every {@code app.providers.poll-interval}; the same value is the minimum backoff. */
@Configuration
@EnableScheduling
class ProviderPollConfig {

	private final ProviderPollService service;

	private final ProviderCallAdminService admin;

	ProviderPollConfig(ProviderCallRepository calls, ProviderConfigService configs, ObjectProvider<DataProvider> providers,
			ProviderCallbackService completion, ProviderCallAdminService admin, @Value("${app.providers.poll-interval:PT10S}") Duration interval,
			@Value("${app.providers.poll-max-delay:PT5M}") Duration maxDelay,
			@Value("${app.providers.max-attempts:5}") int maxAttempts, ProviderMetrics metrics) {
		this.admin = admin;
		this.service = new ProviderPollService(calls, configs,
				providers.orderedStream().collect(Collectors.toMap(DataProvider::key, p -> p)), completion,
				Clock.systemUTC(), interval, maxDelay, maxAttempts, metrics);
	}

	@Scheduled(fixedDelayString = "${app.providers.poll-interval:PT10S}")
	void run() {
		// first, so calls of ended executions are never polled
		admin.sweep();
		service.pollDue();
	}

}
