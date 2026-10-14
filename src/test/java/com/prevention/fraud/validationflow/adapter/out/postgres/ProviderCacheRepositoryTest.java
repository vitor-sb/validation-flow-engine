package com.prevention.fraud.validationflow.adapter.out.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.prevention.fraud.validationflow.application.provider.CachePolicyService;
import com.prevention.fraud.validationflow.application.provider.ProviderCache;
import com.prevention.fraud.validationflow.application.provider.ProviderCachePolicy;
import com.prevention.fraud.validationflow.application.provider.ProviderCacheRepository;

@Testcontainers(disabledWithoutDocker = true)
class ProviderCacheRepositoryTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16");

	@Test
	void storesOnlyWithPolicyAndIsolatesTenantAndProvider() {
		Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).load().migrate();
		var repo = JpaTestContext.start(pg).getBean(ProviderCacheRepository.class);
		var cache = new ProviderCache(repo, new CachePolicyService(repo, Map.of("LOW_VOLATILITY", Duration.ofDays(1)), Duration.ofDays(7)), Duration.ofMinutes(5));
		var now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		var params = Map.<String, Object>of("document", "12345678901", "kind", "cpf");
		var reordered = Map.<String, Object>of("kind", "cpf", "document", "12345678901");
		var data = Map.<String, Object>of("found", true);

		// no policy: nothing cached
		assertNull(cache.store("tenant-a", "p1", "lookup", params, data, now));
		assertTrue(cache.find("tenant-a", "p1", "lookup", params).isEmpty());

		repo.savePolicy(new ProviderCachePolicy("tenant-a", "p1", "lookup", "LOW_VOLATILITY", 60, false, null, null, null), "tester");
		assertNotNull(cache.store("tenant-a", "p1", "lookup", params, data, now));
		var hit = cache.find("tenant-a", "p1", "lookup", reordered).orElseThrow();
		assertEquals(data, hit.response());
		assertEquals(now.plusSeconds(60), hit.validUntil());
		assertFalse(hit.requestKey().contains("12345678901"));

		// storing again replaces the entry (unique key)
		assertNotNull(cache.store("tenant-a", "p1", "lookup", params, Map.of("found", false), now.plusSeconds(5)));
		assertEquals(Map.of("found", false), cache.find("tenant-a", "p1", "lookup", params).orElseThrow().response());

		assertTrue(cache.find("tenant-b", "p1", "lookup", params).isEmpty());
		assertTrue(cache.find("tenant-a", "p2", "lookup", params).isEmpty());
		assertNotEquals(ProviderCache.requestKey(params), ProviderCache.requestKey(Map.of("document", "other")));
	}


	@Test
	void purgesOnlyEntriesExpiredBeforeCutoffAndCountsLookups() {
		Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).load().migrate();
		var repo = JpaTestContext.start(pg).getBean(ProviderCacheRepository.class);
		var seen = new java.util.ArrayList<String>();
		var metrics = new com.prevention.fraud.validationflow.application.provider.ProviderMetrics() {
			public void callback(String p, String o) { }
			public void poll(String p, String o) { }
			public void cache(String p, String r) { seen.add(p + ":" + r); }
			public void gauges(java.util.function.LongSupplier a, java.util.function.LongSupplier b) { }
		};
		var cache = new ProviderCache(repo, new CachePolicyService(repo, Map.of("LOW_VOLATILITY", Duration.ofDays(1)), Duration.ofDays(7)), Duration.ofMinutes(5), metrics);
		var now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		repo.savePolicy(new ProviderCachePolicy("tenant-purge", "pp", "default", "LOW_VOLATILITY", 60, false, null, null, null), "tester");
		var old = Map.<String, Object>of("document", "old");
		var fresh = Map.<String, Object>of("document", "fresh");
		cache.store("tenant-purge", "pp", "default", old, Map.of("found", true), now.minus(Duration.ofDays(3)));
		cache.store("tenant-purge", "pp", "default", fresh, Map.of("found", true), now);

		assertEquals("MISS", cache.lookup("tenant-purge", "pp", "default", Map.of("document", "x"), false, now).status());
		assertEquals("HIT", cache.lookup("tenant-purge", "pp", "default", fresh, false, now).status());
		assertEquals("EXPIRED", cache.lookup("tenant-purge", "pp", "default", old, false, now).status());
		assertEquals("BYPASS", cache.lookup("tenant-purge", "pp", "default", old, true, now).status());
		assertEquals(java.util.List.of("pp:miss", "pp:hit", "pp:expired", "pp:bypass"), seen);

		assertTrue(repo.deleteEntriesExpiredBefore(now.minus(Duration.ofDays(1))) >= 1);
		assertTrue(cache.find("tenant-purge", "pp", "default", old).isEmpty());
		assertTrue(cache.find("tenant-purge", "pp", "default", fresh).isPresent());
	}

}
