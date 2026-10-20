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

	/** Two operations; every operation-aware method answers with the operation it received. */
	@Test
	void engineVariantsReceiveTheOperation() {
		DataProvider multi = new DataProvider() {
			public String key() {
				return "multi";
			}

			@Override
			public java.util.List<OperationSpec> operations() {
				return java.util.List.of(new OperationSpec("a", java.util.Set.of("x"), java.util.Set.of()),
						new OperationSpec("b", java.util.Set.of("y"), java.util.Set.of()));
			}

			public Map<String, Object> fetch(ProviderConfig c, Map<String, Object> p) {
				throw new AssertionError("legacy form must not be called");
			}

			@Override
			public Map<String, Object> fetch(ProviderConfig c, String op, Map<String, Object> p) {
				return Map.of("op", op);
			}

			@Override
			public ProviderResult start(ProviderConfig c, String op, Map<String, Object> p, CallbackHandle cb) {
				return new ProviderResult.Pending("ref-" + op, ProviderResult.Mode.POLL, Duration.ofSeconds(1));
			}

			@Override
			public ProviderResult poll(ProviderConfig c, String op, String ref) {
				return new ProviderResult.Done(Map.of("op", op));
			}

			@Override
			public Map<String, Object> normalizeCallback(ProviderConfig c, String op, Map<String, Object> body) {
				return Map.of("op", op);
			}
		};
		var cb = new CallbackHandle(java.util.UUID.randomUUID(), "t");
		assertThat(multi.fetch(CFG, "b", Map.of())).isEqualTo(Map.of("op", "b"));
		assertThat(multi.start(CFG, "b", Map.of(), cb)).isEqualTo(new ProviderResult.Pending("ref-b", ProviderResult.Mode.POLL, Duration.ofSeconds(1)));
		assertThat(multi.poll(CFG, "a", "r")).isEqualTo(new ProviderResult.Done(Map.of("op", "a")));
		assertThat(multi.normalizeCallback(CFG, "a", Map.of())).isEqualTo(Map.of("op", "a"));
		assertThat(FAKE.operations()).containsExactly(OperationSpec.legacy());
	}

}
