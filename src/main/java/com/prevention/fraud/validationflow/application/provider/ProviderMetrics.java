package com.prevention.fraud.validationflow.application.provider;

import java.util.function.LongSupplier;

/** Port for the async provider events worth counting; implemented outside application. */
public interface ProviderMetrics {

	ProviderMetrics NONE = new ProviderMetrics() {
		public void callback(String provider, String outcome) { }
		public void poll(String provider, String outcome) { }
		public void cache(String provider, String result) { }
		public void gauges(LongSupplier pending, LongSupplier dead) { }
	};

	void callback(String provider, String outcome);

	void poll(String provider, String outcome);

	/** Cache lookup result: hit, miss, expired, bypass or stale_fallback. */
	void cache(String provider, String result);

	/** Registers the gauges of PENDING and DEAD calls (all tenants), read lazily. */
	void gauges(LongSupplier pending, LongSupplier dead);

}
