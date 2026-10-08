package com.prevention.fraud.validationflow.application.execution;

import com.prevention.fraud.validationflow.application.execution.ports.ExecutionMetrics;
import com.prevention.fraud.validationflow.application.masking.LogMasker;
import com.prevention.fraud.validationflow.application.validator.ValidatorException;
import com.prevention.fraud.validationflow.application.validator.ValidatorStrategy;
import com.prevention.fraud.validationflow.domain.flow.GraphValidator;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Invokes a validator with timeout, retry and backoff; reports every attempt through an {@link AttemptRecorder}. */
public class ValidatorRunner {

	private static final Logger log = LoggerFactory.getLogger(ValidatorRunner.class);

	/** Receives one call per attempt, so the caller can persist one node_execution row each. */
	@FunctionalInterface
	public interface AttemptRecorder {

		void record(int attempt, String status, Map<String, Object> output, Map<String, Object> error, Instant started);

	}

	record Attempt(ValidatorStrategy.ValidationResult result, Map<String, Object> error, int attempts) {
	}

	private final ExecutionMetrics meters;

	// only used when a node has config.timeout. Bounded: a validator that ignores interrupt keeps its thread, so
	// without a cap hung validators could exhaust the JVM. Daemon threads so they never block shutdown.
	private final ThreadPoolExecutor timeoutPool;

	public ValidatorRunner(ExecutionMetrics meters, int maxThreads, int queueCapacity) {
		this.meters = meters;
		this.timeoutPool = new ThreadPoolExecutor(maxThreads, maxThreads, 60, TimeUnit.SECONDS,
				new ArrayBlockingQueue<>(queueCapacity), r -> {
					Thread t = new Thread(r, "validator-timeout");
					t.setDaemon(true);
					return t;
				});
		timeoutPool.allowCoreThreadTimeOut(true);
	}

	/** Runs the validator up to retryPolicy.maxAttempts times. */
	Attempt run(String nodeId, ValidatorStrategy v, Map<String, Object> ctx, NodeConfig config,
			AttemptRecorder recorder) {
		int max = config.retryPolicy().maxAttempts();
		Duration timeout = config.timeout();
		for (int attempt = 1;; attempt++) {
			String status = "FAILED";
			Map<String, Object> error;
			Instant started = Instant.now();
			try {
				var r = call(v, ctx, config.raw(), timeout);
				recorder.record(attempt, r.success() ? "COMPLETED" : "FAILED", r.output() == null ? Map.of() : r.output(), null,
						started);
				return new Attempt(r, null, attempt);
			}
			catch (TimeoutException e) {
				meters.nodeTimeout(v.key());
				status = "TIMED_OUT";
				error = ExecutionService.error("NODE_TIMEOUT", "node " + nodeId + " exceeded " + timeout, true);
			}
			catch (ValidatorException e) {
				error = ExecutionService.error(e.code(), String.valueOf(e.getMessage()), e.retryable());
			}
			catch (RuntimeException e) {
				error = unexpected(nodeId, e, ctx);
			}
			recorder.record(attempt, status, Map.of(), error, started);
			meters.nodeError(v.key(), (String) error.get("code"));
			if (!(Boolean) error.get("retryable") || attempt >= max) {
				return new Attempt(null, error, attempt);
			}
			meters.nodeRetry(v.key());
			if (!sleep(backoffMillis(config.retryPolicy().delay(), config.retryPolicy().backoffExponential(), attempt))) {
				return new Attempt(null, ExecutionService.error("INTERRUPTED", "interrupted while backing off", false),
						attempt);
			}
		}
	}

	private static Map<String, Object> unexpected(String nodeId, RuntimeException e, Map<String, Object> ctx) {
		log.warn("validator error node={} class={} message={}", nodeId, e.getClass().getName(),
				LogMasker.redact(String.valueOf(e.getMessage()), LogMasker.credentialValues(ctx)));
		Map<String, Object> error = new LinkedHashMap<>(ExecutionService.error("VALIDATOR_ERROR", ExecutionService.UNEXPECTED, false));
		error.put("exception", e.getClass().getName());
		return error;
	}

	private static boolean sleep(long ms) {
		try {
			Thread.sleep(ms);
			return true;
		}
		catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/** Exponent capped so the shift/multiply can't overflow; never exceeds the validator's max delay. */
	static long backoffMillis(Duration delay, boolean exponential, int attempt) {
		return Math.min(delay.toMillis() * (exponential ? 1L << Math.min(attempt - 1, 20) : 1L),
				GraphValidator.DEFAULT_MAX_DELAY.toMillis());
	}

	private ValidatorStrategy.ValidationResult call(ValidatorStrategy v, Map<String, Object> ctx,
			Map<String, Object> config, Duration timeout) throws TimeoutException {
		var input = new ValidatorStrategy.ValidationInput(Map.copyOf(ctx), config);
		if (timeout == null) {
			return v.execute(input);
		}
		Future<ValidatorStrategy.ValidationResult> f;
		try {
			f = timeoutPool.submit(() -> v.execute(input));
		}
		catch (RejectedExecutionException e) {
			meters.nodeRejected(v.key());
			throw new ValidatorException("NODE_REJECTED", "validator capacity exhausted, try again later", true);
		}
		try {
			return f.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
		}
		catch (TimeoutException e) {
			f.cancel(true);
			throw e;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
		catch (ExecutionException e) {
			throw e.getCause() instanceof RuntimeException re ? re : new IllegalStateException(e.getCause());
		}
	}

}
