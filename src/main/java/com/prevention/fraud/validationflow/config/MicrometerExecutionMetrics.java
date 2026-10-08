package com.prevention.fraud.validationflow.config;

import com.prevention.fraud.validationflow.application.execution.ports.ExecutionMetrics;
import io.micrometer.core.instrument.MeterRegistry;

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
	public void nodeRejected(String validator) {
		meters.counter("validation.node.rejected", "validator", validator).increment();
	}

}
