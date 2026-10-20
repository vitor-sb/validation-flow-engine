package com.prevention.fraud.validationflow.adapter.out.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.prevention.fraud.validationflow.application.provider.ProviderConfig;
import com.prevention.fraud.validationflow.application.provider.ProviderConfigRepository;

@Testcontainers(disabledWithoutDocker = true)
class ProviderConfigRepositoryTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16");

	static ProviderConfig config(String tenant, boolean enabled, String url) {
		return new ProviderConfig(tenant, "brasilapi", enabled, url, 1000, 2000, "BRASILAPI_TOKEN", "admin",
				Instant.now().truncatedTo(ChronoUnit.MILLIS));
	}

	@Test
	void saveReadUpdateAndTenantIsolation() {
		Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).load().migrate();
		var repo = JpaTestContext.start(pg).getBean(ProviderConfigRepository.class);

		var a = config("tenant-a", true, "https://a.example");
		repo.save(a);
		assertEquals(a, repo.find("tenant-a", "brasilapi").orElseThrow());

		var updated = config("tenant-a", false, "https://a2.example");
		repo.save(updated);
		assertEquals(updated, repo.find("tenant-a", "brasilapi").orElseThrow());
		assertEquals(1, repo.findAll("tenant-a").size());

		assertTrue(repo.find("tenant-b", "brasilapi").isEmpty());
		assertTrue(repo.findAll("tenant-b").isEmpty());
		repo.save(config("tenant-b", true, "https://b.example"));
		assertEquals("https://a2.example", repo.find("tenant-a", "brasilapi").orElseThrow().baseUrl());
		assertFalse(repo.find("tenant-a", "brasilapi").orElseThrow().enabled());
	}

}
