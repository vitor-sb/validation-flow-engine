package com.prevention.fraud.validationflow.config;

import com.prevention.fraud.validationflow.application.execution.ports.ExecutionMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;

/** Micrometer adapter for {@link ExecutionMetrics}. */
public class MicrometerExecutionMetrics implements ExecutionMetrics {

	private final MeterRegistry meters;

	public MicrometerExecutionMetrics(MeterRegistry meters) {
		this.meters = meters;
	}

	@Override
	public void nodeTimeout(String validator) {
		meters.counter("validation.node.timeout", "validator", validator).increment();
	}

	@Override
	public void nodeError(String validator, String code) {
		meters.counter("validation.node.error", "validator", validator, "code", code).increment();
	}

	@Override
	public void nodeRetry(String validator) {
		meters.counter("validation.node.retry", "validator", validator).increment();
	}

	@Override
	public void executionFinished(String status, Duration duration) {
		meters.counter("validation.execution", "status", status).increment();
		timer("validation.execution.duration", "status", status).record(duration);
	}

	@Override
	public void nodeFinished(String type, String status, Duration duration) {
		timer("validation.node.duration", "type", type, "status", status).record(duration);
	}

	@Override
	public void resumed() {
		meters.counter("validation.execution.resumed").increment();
	}

	@Override
	public void cancelled() {
		meters.counter("validation.execution.cancelled").increment();
	}

	@Override
	public void waitExpired() {
		meters.counter("validation.execution.wait_expired").increment();
	}

	@Override
	public void waitingGauge(java.util.function.LongSupplier waiting) {
		io.micrometer.core.instrument.Gauge.builder("validation.execution.waiting", waiting::getAsLong).register(meters);
	}

	private Timer timer(String name, String... tags) {
		return Timer.builder(name).tags(tags).publishPercentiles(0.5, 0.95, 0.99).register(meters);
	}

	@Override
	public void nodeRejected(String validator) {
		meters.counter("validation.node.rejected", "validator", validator).increment();
	}

}
