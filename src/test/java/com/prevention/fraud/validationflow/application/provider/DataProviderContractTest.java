package com.prevention.fraud.validationflow.application.provider;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataProviderContractTest {

	private static final ProviderConfig CFG = new ProviderConfig("A", "fake", true, null, null, null, null, "u",
			Instant.EPOCH);

	/** Behaviour chosen by the "mode" param: sync, callback or poll. */
	private static final DataProvider FAKE = new DataProvider() {
		public String key() {
			return "fake";
		}

		public Map<String, Object> fetch(ProviderConfig c, Map<String, Object> p) {
			return Map.of("active", true);
		}

		@Override
		public ProviderResult start(ProviderConfig c, Map<String, Object> p) {
			return switch ((String) p.get("mode")) {
				case "callback" -> new ProviderResult.Pending("ref-1", ProviderResult.Mode.CALLBACK, null);
				case "poll" -> new ProviderResult.Pending("ref-2", ProviderResult.Mode.POLL, Duration.ofSeconds(5));
				default -> DataProvider.super.start(c, p);
			};
		}

		@Override
		public ProviderResult poll(ProviderConfig c, String ref) {
			if (ref.equals("bad")) {
				throw new ProviderException("PROVIDER_ERROR", "boom", true);
			}
			return new ProviderResult.Done(Map.of("ref", ref));
		}
	};

	@Test
	void defaultStartWrapsFetchInDone() {
		assertThat(FAKE.start(CFG, Map.of("mode", "sync"))).isEqualTo(new ProviderResult.Done(Map.of("active", true)));
	}

	@Test
	void pendingCallback() {
		assertThat(FAKE.start(CFG, Map.of("mode", "callback")))
			.isEqualTo(new ProviderResult.Pending("ref-1", ProviderResult.Mode.CALLBACK, null));
	}

	@Test
	void pendingPollThenDone() {
		var pending = (ProviderResult.Pending) FAKE.start(CFG, Map.of("mode", "poll"));
		assertThat(pending.mode()).isEqualTo(ProviderResult.Mode.POLL);
		assertThat(pending.pollAfter()).isEqualTo(Duration.ofSeconds(5));
		assertThat(FAKE.poll(CFG, pending.externalRef())).isEqualTo(new ProviderResult.Done(Map.of("ref", "ref-2")));
	}

	@Test
	void failuresUseProviderException() {
		assertThatThrownBy(() -> FAKE.poll(CFG, "bad")).isInstanceOf(ProviderException.class)
			.extracting("retryable").isEqualTo(true);
	}

	@Test
	void syncProviderCannotBePolled() {
		DataProvider sync = new DataProvider() {
			public String key() {
				return "s";
			}

			public Map<String, Object> fetch(ProviderConfig c, Map<String, Object> p) {
				return Map.of();
			}
		};
		assertThatThrownBy(() -> sync.poll(CFG, "x")).isInstanceOf(UnsupportedOperationException.class);
	}

}
