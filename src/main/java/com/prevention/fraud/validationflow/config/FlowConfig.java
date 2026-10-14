package com.prevention.fraud.validationflow.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.prevention.fraud.validationflow.application.execution.ports.ExecutionRepository;
import com.prevention.fraud.validationflow.application.execution.ports.IdempotencyRepository;
import com.prevention.fraud.validationflow.application.execution.ExecutionService;
import com.prevention.fraud.validationflow.application.execution.idempotency.IdempotentExecutionService;
import com.prevention.fraud.validationflow.application.execution.node.ValidatorRunner;
import com.prevention.fraud.validationflow.application.flow.ports.FlowRepository;
import com.prevention.fraud.validationflow.application.execution.node.NodeHandlers;
import com.prevention.fraud.validationflow.application.execution.node.SubFlowRunner;
import com.prevention.fraud.validationflow.application.flow.FlowService;
import com.prevention.fraud.validationflow.application.provider.CachePolicyService;
import com.prevention.fraud.validationflow.application.provider.DataProvider;
import com.prevention.fraud.validationflow.application.provider.ProviderCache;
import com.prevention.fraud.validationflow.application.provider.ProviderCacheRepository;
import com.prevention.fraud.validationflow.application.provider.ProviderCall;
import com.prevention.fraud.validationflow.application.provider.ProviderCallRepository;
import com.prevention.fraud.validationflow.application.provider.ProviderMetrics;
import com.prevention.fraud.validationflow.application.provider.CredentialResolver;
import com.prevention.fraud.validationflow.application.execution.ProviderCallAdminService;
import com.prevention.fraud.validationflow.application.execution.ProviderCallbackService;
import com.prevention.fraud.validationflow.application.provider.ProviderConfigService;
import com.prevention.fraud.validationflow.application.validator.ValidatorRegistry;
import com.prevention.fraud.validationflow.application.validator.ValidatorStrategy;
import com.prevention.fraud.validationflow.domain.flow.GraphValidator;

@Configuration
class FlowConfig {

	@Bean
	FlowService flowService(FlowRepository repository, GraphValidator graphValidator, ValidatorRegistry registry,
			com.prevention.fraud.validationflow.application.provider.ProviderConfigService providers) {
		return new FlowService(repository, graphValidator, registry, providers);
	}

	@Bean
	ExecutionService executionService(FlowService flows, ExecutionRepository repository, ValidatorRegistry registry,
			io.micrometer.core.instrument.MeterRegistry meters, ObjectProvider<DataProvider> providers,
			ProviderConfigService providerConfigs, ProviderCallRepository calls, ProviderCache cache,
			// caps threads stuck in validators that ignore interrupt; size to expected concurrent timed validators
			@org.springframework.beans.factory.annotation.Value("${app.validators.max-threads:64}") int maxThreads,
			@org.springframework.beans.factory.annotation.Value("${app.validators.queue-capacity:128}") int queueCapacity) {
		var runner = new ValidatorRunner(new MicrometerExecutionMetrics(meters), maxThreads, queueCapacity);
		java.util.Map<String, DataProvider> byKey = new java.util.HashMap<>();
		providers.orderedStream().forEach(p -> byKey.put(p.key(), p));
		var handlers = new java.util.ArrayList<>(NodeHandlers.defaults(registry, runner, new SubFlowRunner(flows)));
		handlers.add(new NodeHandlers.ExternalCall(byKey, providerConfigs, calls, cache));
		return new ExecutionService(flows, repository, handlers, runner.metrics());
	}

	@Bean
	CachePolicyService cachePolicyService(ProviderCacheRepository repository,
			@org.springframework.beans.factory.annotation.Value("${app.providers.cache-ttl.low-volatility:P1D}") java.time.Duration low,
			@org.springframework.beans.factory.annotation.Value("${app.providers.cache-ttl.medium-volatility:PT1H}") java.time.Duration medium,
			@org.springframework.beans.factory.annotation.Value("${app.providers.cache-ttl.high-volatility:PT5M}") java.time.Duration high,
			@org.springframework.beans.factory.annotation.Value("${app.providers.cache-max-ttl:P7D}") java.time.Duration maxTtl) {
		return new CachePolicyService(repository, java.util.Map.of(CachePolicyService.LOW, low,
				CachePolicyService.MEDIUM, medium, CachePolicyService.HIGH, high), maxTtl);
	}

	@Bean
	ProviderCache providerCache(ProviderCacheRepository repository, CachePolicyService policies, ProviderMetrics metrics,
			@org.springframework.beans.factory.annotation.Value("${app.providers.negative-cache-ttl:PT5M}") java.time.Duration negativeTtl) {
		return new ProviderCache(repository, policies, negativeTtl, metrics);
	}

	@Bean
	ProviderMetrics providerMetrics(io.micrometer.core.instrument.MeterRegistry meters, ProviderCallRepository calls) {
		var metrics = new MicrometerProviderMetrics(meters);
		metrics.gauges(() -> calls.countByStatus(ProviderCall.Status.PENDING), () -> calls.countByStatus(ProviderCall.Status.DEAD));
		return metrics;
	}

	@Bean
	ProviderCallbackService providerCallbackService(ProviderCallRepository calls, ProviderConfigService configs,
			ObjectProvider<DataProvider> providers, CredentialResolver credentials, ExecutionService executions, ProviderMetrics metrics) {
		return new ProviderCallbackService(calls, configs,
				providers.orderedStream().collect(java.util.stream.Collectors.toMap(DataProvider::key, p -> p)),
				credentials, executions, java.time.Clock.systemUTC(), metrics);
	}

	@Bean
	ProviderCallAdminService providerCallAdminService(ProviderCallRepository calls, ExecutionService executions) {
		return new ProviderCallAdminService(calls, executions, java.time.Clock.systemUTC());
	}

	@Bean
	IdempotentExecutionService idempotentExecutionService(ExecutionService executions, IdempotencyRepository repository,
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
			@org.springframework.beans.factory.annotation.Value("${app.limits.max-timeout:PT5M}") java.time.Duration maxTimeout,
			@org.springframework.beans.factory.annotation.Value("${app.limits.max-wait:P7D}") java.time.Duration maxWait) {
		return new GraphValidator(registry::contains, maxNodes, maxTransitions, maxAttempts, maxDelay, maxTimeout, maxWait);
	}

}
