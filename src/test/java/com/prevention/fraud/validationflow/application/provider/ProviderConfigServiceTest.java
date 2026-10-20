package com.prevention.fraud.validationflow.application.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class ProviderConfigServiceTest {

	private final Map<String, ProviderConfig> db = new HashMap<>();
	private final ProviderConfigRepository repository = new ProviderConfigRepository() {
		public Optional<ProviderConfig> find(String t, String k) {
			return Optional.ofNullable(db.get(t + k));
		}

		public List<ProviderConfig> findAll(String t) {
			return List.of();
		}

		public void save(ProviderConfig c) {
			db.put(c.tenantId() + c.providerKey(), c);
		}
	};
	private Instant now = Instant.parse("2026-01-01T00:00:00Z");
	private final Clock clock = new Clock() {
		public Instant instant() {
			return now;
		}

		public java.time.ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		public Clock withZone(java.time.ZoneId z) {
			return this;
		}
	};
	private final ProviderConfigService service = new ProviderConfigService(repository, Duration.ofSeconds(30), clock);

	private static ProviderConfig config(String tenant, boolean enabled) {
		return new ProviderConfig(tenant, "p", enabled, null, null, null, null, "u", Instant.EPOCH);
	}

	@Test
	void missingRowCountsAsDisabled() {
		assertThat(service.resolve("t1", "p")).isEmpty();
	}

	@Test
	void disabledRowCountsAsDisabled() {
		repository.save(config("t1", false));
		assertThat(service.resolve("t1", "p")).isEmpty();
	}

	@Test
	void changeInDatabaseTakesEffectAfterTtlWithoutRestart() {
		repository.save(config("t1", false));
		assertThat(service.resolve("t1", "p")).isEmpty();

		repository.save(config("t1", true));
		now = now.plusSeconds(29);
		assertThat(service.resolve("t1", "p")).isEmpty(); // still cached
		now = now.plusSeconds(2);
		assertThat(service.resolve("t1", "p")).isPresent();
	}

	@Test
	void invalidateTakesEffectImmediately() {
		repository.save(config("t1", false));
		service.resolve("t1", "p");
		repository.save(config("t1", true));
		service.invalidate("t1", "p");
		assertThat(service.resolve("t1", "p")).isPresent();
	}

	@Test
	void tenantsAreIndependent() {
		repository.save(config("t1", true));
		assertThat(service.resolve("t1", "p")).isPresent();
		assertThat(service.resolve("t2", "p")).isEmpty();
	}
}
