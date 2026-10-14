package com.prevention.fraud.validationflow.config;

import java.util.function.LongSupplier;

import com.prevention.fraud.validationflow.application.provider.ProviderMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/** Micrometer adapter for {@link ProviderMetrics}. */
public class MicrometerProviderMetrics implements ProviderMetrics {

	private final MeterRegistry meters;

	public MicrometerProviderMetrics(MeterRegistry meters) {
		this.meters = meters;
	}

	@Override
	public void callback(String provider, String outcome) {
		meters.counter("validation.provider.callback", "provider", provider, "outcome", outcome).increment();
	}

	@Override
	public void poll(String provider, String outcome) {
		meters.counter("validation.provider.poll", "provider", provider, "outcome", outcome).increment();
	}

	@Override
	public void cache(String provider, String result) {
		meters.counter("validation.provider.cache", "provider", provider, "result", result).increment();
	}

	@Override
	public void gauges(LongSupplier pending, LongSupplier dead) {
		Gauge.builder("validation.provider.pending", pending::getAsLong).register(meters);
		Gauge.builder("validation.provider.dead", dead::getAsLong).register(meters);
	}

}
