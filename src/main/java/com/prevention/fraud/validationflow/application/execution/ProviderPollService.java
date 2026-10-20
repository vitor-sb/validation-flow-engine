package com.prevention.fraud.validationflow.application.execution;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.prevention.fraud.validationflow.application.flow.FlowException;
import com.prevention.fraud.validationflow.application.provider.DataProvider;
import com.prevention.fraud.validationflow.application.provider.ProviderCall;
import com.prevention.fraud.validationflow.application.provider.ProviderCallRepository;
import com.prevention.fraud.validationflow.application.provider.ProviderConfigService;
import com.prevention.fraud.validationflow.application.provider.ProviderException;
import com.prevention.fraud.validationflow.application.provider.ProviderMetrics;
import com.prevention.fraud.validationflow.application.provider.ProviderResult;

/** Polls PENDING POLL-mode calls that are due and resumes their executions, sharing the callback's continuation. */
public class ProviderPollService {

	private static final Logger log = LoggerFactory.getLogger(ProviderPollService.class);

	private static final int BATCH = 50;

	/** A claimed call not finished within this comes back to the next run (instance crash). */
	private static final Duration LEASE = Duration.ofMinutes(1);

	private final ProviderCallRepository calls;
	private final ProviderConfigService configs;
	private final Map<String, DataProvider> providers;
	private final ProviderCallbackService completion;
	private final Clock clock;
	private final Duration minDelay;
	private final Duration maxDelay;
	private final int maxAttempts;
	private final ProviderMetrics metrics;

	public ProviderPollService(ProviderCallRepository calls, ProviderConfigService configs,
			Map<String, DataProvider> providers, ProviderCallbackService completion, Clock clock, Duration minDelay,
			Duration maxDelay, int maxAttempts, ProviderMetrics metrics) {
		this.calls = calls;
		this.configs = configs;
		this.providers = providers;
		this.completion = completion;
		this.clock = clock;
		this.minDelay = minDelay;
		this.maxDelay = maxDelay;
		this.maxAttempts = maxAttempts;
		this.metrics = metrics;
	}

	/** Returns how many calls were examined. */
	public int pollDue() {
		var now = clock.instant();
		var due = calls.claimDuePolls(now, now.plus(LEASE), BATCH);
		due.forEach(this::pollOne);
		return due.size();
	}

	private void pollOne(ProviderCall call) {
		var now = clock.instant();
		var config = configs.resolve(call.tenantId(), call.providerKey()).orElse(null);
		var provider = providers.get(call.providerKey());
		if (config == null || provider == null || !call.deadline().isAfter(now)) {
			// disabled provider counts as a non-retryable failure; past the deadline the wait expiry owns the execution
			String reason = config == null || provider == null ? "PROVIDER_DISABLED" : "DEADLINE_PASSED";
			calls.save(call.withState(ProviderCall.Status.FAILED, call.attempts(), null, now, reason));
			observe(call, "failed", "FAILED", reason);
			return;
		}
		// capped exponential backoff: wait twice as long as since the last attempt
		var delay = Duration.between(call.updatedAt(), now).multipliedBy(2);
		delay = delay.compareTo(minDelay) < 0 ? minDelay : delay.compareTo(maxDelay) > 0 ? maxDelay : delay;
		try {
			var result = provider.poll(config, call.operation(), call.externalRef());
			if (result instanceof ProviderResult.Done d) {
				completion.complete(call, d.data());
				observe(call, "done", "COMPLETED", null);
			}
			else {
				calls.save(call.withState(ProviderCall.Status.PENDING, call.attempts(), now.plus(delay), now));
				observe(call, "pending", "PENDING", null);
			}
		}
		catch (ProviderException e) {
			int attempts = call.attempts() + 1;
			boolean giveUp = !e.retryable() || attempts > maxAttempts;
			calls.save(call.withState(giveUp ? ProviderCall.Status.FAILED : ProviderCall.Status.PENDING, attempts,
					giveUp ? null : now.plus(delay), now, giveUp ? (e.retryable() ? "RETRIES_EXHAUSTED" : "NON_RETRYABLE") : null));
			observe(call, giveUp ? "failed" : "retry", giveUp ? "FAILED" : "PENDING",
					giveUp ? (e.retryable() ? "RETRIES_EXHAUSTED" : "NON_RETRYABLE") : null);
		}
		catch (FlowException e) {
			// complete() already marked the call FAILED: the execution expired, was cancelled or moved on
			observe(call, "conflict", "FAILED", "EXECUTION_ENDED");
		}
	}

	/** Never logs the provider body or the call's token. */
	private void observe(ProviderCall call, String outcome, String status, String reason) {
		metrics.poll(call.providerKey(), outcome);
		log.info("provider poll executionId={} callId={} providerKey={} status={} reason={}", call.executionId(), call.id(),
				call.providerKey(), status, reason);
	}

}
