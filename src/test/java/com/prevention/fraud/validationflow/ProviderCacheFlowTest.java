package com.prevention.fraud.validationflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import com.prevention.fraud.validationflow.application.execution.ExecutionService;
import com.prevention.fraud.validationflow.application.flow.FlowService;
import com.prevention.fraud.validationflow.application.provider.DataProvider;
import com.prevention.fraud.validationflow.application.provider.ProviderCache;
import com.prevention.fraud.validationflow.application.provider.ProviderCacheRepository;
import com.prevention.fraud.validationflow.application.provider.ProviderCachePolicy;
import com.prevention.fraud.validationflow.application.provider.ProviderConfig;
import com.prevention.fraud.validationflow.application.provider.ProviderException;
import com.prevention.fraud.validationflow.application.provider.ProviderConfigService;
import com.prevention.fraud.validationflow.application.validator.ProviderBackedValidator;
import com.prevention.fraud.validationflow.application.validator.ValidatorStrategy.ValidationInput;

/** Cache in the call path: a counting sync provider behind EXTERNAL_CALL and behind a provider-backed validator. */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, ProviderCacheFlowTest.Fake.class })
class ProviderCacheFlowTest {

	static final AtomicInteger FETCHES = new AtomicInteger();

	static volatile boolean DOWN, DOWN_RETRYABLE;

	@TestConfiguration(proxyBeanMethods = false)
	static class Fake {
		@Bean
		DataProvider fakeCacheProvider() {
			return new DataProvider() {
				public String key() {
					return "fake-cache";
				}

				public Map<String, Object> fetch(ProviderConfig c, Map<String, Object> p) {
					FETCHES.incrementAndGet();
					if (DOWN) {
						throw new ProviderException("UPSTREAM_DOWN", "provider unavailable", DOWN_RETRYABLE);
					}
					return Map.of("active", true);
				}
			};
		}
	}

	@Autowired
	FlowService flows;

	@Autowired
	ExecutionService executions;

	@Autowired
	ProviderConfigService configs;

	@Autowired
	ProviderCacheRepository repo;

	@Autowired
	ProviderCache cache;

	@Autowired
	List<DataProvider> providers;

	private void enable(String tenant, boolean withPolicy) {
		enable(tenant, withPolicy, false);
	}

	private void enable(String tenant, boolean withPolicy, boolean fallback) {
		configs.save(new ProviderConfig(tenant, "fake-cache", true, null, null, null, null, null, "t", null));
		if (withPolicy) {
			repo.savePolicy(new ProviderCachePolicy(tenant, "fake-cache", ProviderCache.OPERATION, "LOW_VOLATILITY", 3600,
					fallback, null, null, null), "t");
		}
	}

	/** Expired entry for doc "d1" (the key `run` queries), then the provider goes down. */
	private void expireThenBreak(String tenant, boolean retryable) {
		cache.store(tenant, "fake-cache", ProviderCache.OPERATION, Map.of("id", "d1"), Map.of("active", true),
				Instant.now().minusSeconds(7200));
		DOWN = true;
		DOWN_RETRYABLE = retryable;
	}

	private String run(String tenant, String doc, String cacheMode) {
		return run(tenant, doc, cacheMode, null);
	}

	private String run(String tenant, String doc, String cacheMode, String operation) {
		Map<String, Object> config = new java.util.HashMap<>(Map.of("providerKey", "fake-cache", "query", Map.of("id", "doc"),
				"expect", Map.of("active", true), "maxWait", "PT1H"));
		if (cacheMode != null) {
			config.put("cache", cacheMode);
		}
		if (operation != null) {
			config.put("operation", operation);
		}
		Map<String, Object> g = Map.of("startNodeId", "s", "nodes", Map.of(
				"s", Map.of("type", "START", "transitions", List.of(Map.of("to", "x"))),
				"x", Map.of("type", "EXTERNAL_CALL", "config", config, "transitions", List.of(Map.of("to", "ok"))),
				"ok", Map.of("type", "END", "config", Map.of("decision", "APPROVED"))));
		String ctx = "C-" + tenant + "-" + cacheMode + "-" + operation;
		var d = flows.createDraft(tenant, "t", new FlowService.CreateFlow("k-" + ctx + System.nanoTime(), "CF", ctx, "d", null, g, List.of(), null));
		flows.activate(tenant, d.id());
		var result = executions.execute(tenant, null, "CF", ctx, Map.of("doc", doc), null).result();
		return result == null ? "FAILED" : String.valueOf(result.get("nodes"));
	}

	private static String status(String nodes) {
		var m = java.util.regex.Pattern.compile("cacheStatus=(\\w+)").matcher(nodes);
		assertTrue(m.find(), nodes);
		return m.group(1);
	}

	@Test
	void externalCallHitsBypassesAndSeparatesParameters() {
		enable("cache-a", true);
		int base = FETCHES.get();
		assertEquals("MISS", status(run("cache-a", "d1", null)));
		String hit = run("cache-a", "d1", null);
		assertEquals("HIT", status(hit));
		assertTrue(hit.contains("cachePolicy=LOW_VOLATILITY") && hit.contains("cacheAgeSeconds="), hit);
		assertEquals(base + 1, FETCHES.get(), "second identical execution must not call the provider");
		assertEquals("MISS", status(run("cache-a", "d2", null)));
		assertEquals(base + 2, FETCHES.get());
		assertEquals("BYPASS", status(run("cache-a", "d1", "BYPASS")));
		assertEquals(base + 3, FETCHES.get());
		assertEquals("HIT", status(run("cache-a", "d1", null)));
		assertEquals(base + 3, FETCHES.get());
	}

	@Test
	void eachOperationUsesItsOwnPolicyAndDefaultStillWorks() {
		enable("cache-op", true);
		repo.savePolicy(new ProviderCachePolicy("cache-op", "fake-cache", "lookup", "HIGH_VOLATILITY", 60, false, null, null, null), "t");
		int base = FETCHES.get();
		var first = run("cache-op", "d1", null, "lookup");
		assertEquals("MISS", status(first));
		assertTrue(first.contains("cacheOperation=lookup") && first.contains("cachePolicy=HIGH_VOLATILITY"), first);
		assertEquals("HIT", status(run("cache-op", "d1", null, "lookup")));
		// same query, other operation: its own entry (and policy)
		var def = run("cache-op", "d1", null);
		assertEquals("MISS", status(def));
		assertTrue(def.contains("cacheOperation=default") && def.contains("cachePolicy=LOW_VOLATILITY"), def);
		assertEquals(base + 2, FETCHES.get());
		// an operation with no policy is not cached
		assertEquals("DISABLED", status(run("cache-op", "d1", null, "other")));
	}

	@Test
	void noPolicyDisablesAndOtherTenantDoesNotReuse() {
		enable("cache-b", false);
		int base = FETCHES.get();
		assertEquals("DISABLED", status(run("cache-b", "d1", null)));
		assertEquals("DISABLED", status(run("cache-b", "d1", null)));
		assertEquals(base + 2, FETCHES.get());
		enable("cache-c", true);
		run("cache-c", "d1", null);
		enable("cache-d", true);
		assertEquals("MISS", status(run("cache-d", "d1", null)));
	}

	@Test
	void validatorUsesCacheAndExpiredEntryIsRefreshed() {
		enable("cache-e", true);
		var provider = providers.stream().filter(p -> p.key().equals("fake-cache")).findFirst().orElseThrow();
		var v = new ProviderBackedValidator("v", provider, configs, cache);
		var in = new ValidationInput(Map.of("inputData", Map.of("doc", "x")),
				Map.of("params", Map.of("query", Map.of("id", "doc"))), "cache-e");
		int base = FETCHES.get();
		assertEquals("MISS", v.execute(in).output().get("cacheStatus"));
		assertEquals("HIT", v.execute(in).output().get("cacheStatus"));
		assertEquals(base + 1, FETCHES.get());
		// an entry past its validity is EXPIRED and refetched
		cache.store("cache-e", "fake-cache", ProviderCache.OPERATION, Map.of("id", "x"), Map.of("active", true),
				Instant.now().minusSeconds(7200));
		assertEquals("EXPIRED", v.execute(in).output().get("cacheStatus"));
		assertEquals(base + 2, FETCHES.get());
		assertEquals("HIT", v.execute(in).output().get("cacheStatus"));
	}

	@Test
	void externalCallServesStaleOnlyWhenPolicyAllowsAndErrorIsRetryable() {
		enable("cache-f", true, true);
		enable("cache-g", true, false);
		enable("cache-h", true, true);
		try {
			expireThenBreak("cache-f", true);
			String stale = run("cache-f", "d1", null);
			assertEquals("STALE_FALLBACK", status(stale));
			assertTrue(stale.contains("fallbackReason=UPSTREAM_DOWN") && stale.contains("cacheAgeSeconds="), stale);
			expireThenBreak("cache-g", true);
			assertEquals("FAILED", run("cache-g", "d1", null)); // no permission: plain failure
			expireThenBreak("cache-h", false);
			assertEquals("FAILED", run("cache-h", "d1", null)); // not retryable: no fallback
		}
		finally {
			DOWN = false;
		}
	}

	@Test
	void validatorFallsBackOnlyAfterRetriesAreExhausted() {
		enable("cache-i", true, true);
		var provider = providers.stream().filter(p -> p.key().equals("fake-cache")).findFirst().orElseThrow();
		var v = new ProviderBackedValidator("v", provider, configs, cache);
		var in = new ValidationInput(Map.of("inputData", Map.of("doc", "d1")),
				Map.of("params", Map.of("query", Map.of("id", "doc"))), "cache-i");
		try {
			expireThenBreak("cache-i", true);
			var out = v.fallback(in, "UPSTREAM_DOWN").orElseThrow().output();
			assertEquals("STALE_FALLBACK", out.get("cacheStatus"));
			assertEquals("UPSTREAM_DOWN", out.get("fallbackReason"));
			assertTrue(v.fallback(new ValidationInput(in.data(), in.config(), "cache-other"), "X").isEmpty());
		}
		finally {
			DOWN = false;
		}
	}

}
