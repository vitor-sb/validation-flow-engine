package com.prevention.fraud.validationflow;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.prevention.fraud.validationflow.application.execution.ExecutionService;
import com.prevention.fraud.validationflow.application.execution.ProviderCallbackService;
import com.prevention.fraud.validationflow.application.execution.ProviderPollService;
import com.prevention.fraud.validationflow.application.flow.FlowService;
import com.prevention.fraud.validationflow.application.provider.DataProvider;
import com.prevention.fraud.validationflow.application.provider.ProviderCallRepository;
import com.prevention.fraud.validationflow.application.provider.ProviderConfig;
import com.prevention.fraud.validationflow.application.provider.ProviderMetrics;
import com.prevention.fraud.validationflow.application.provider.ProviderConfigService;
import com.prevention.fraud.validationflow.application.provider.ProviderException;
import com.prevention.fraud.validationflow.application.provider.ProviderResult;
import com.prevention.fraud.validationflow.domain.execution.FlowExecution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Polling with a controlled clock and a fake provider; the externalRef (= the "id" parameter) picks the behavior. */
@SpringBootTest(properties = "app.providers.poll-interval=PT1H")
@Import({ TestcontainersConfiguration.class, ProviderPollTest.Fake.class })
class ProviderPollTest {

	static final Map<String, Integer> POLLS = new ConcurrentHashMap<>();

	@TestConfiguration(proxyBeanMethods = false)
	static class Fake {
		@Bean
		DataProvider fakePollProvider() {
			return new DataProvider() {
				public String key() {
					return "fake-poll";
				}

				public Map<String, Object> fetch(ProviderConfig c, Map<String, Object> p) {
					return Map.of();
				}

				public ProviderResult start(ProviderConfig c, Map<String, Object> p) {
					return new ProviderResult.Pending((String) p.get("id"), ProviderResult.Mode.POLL, Duration.ofSeconds(10));
				}

				public ProviderResult poll(ProviderConfig c, String ref) {
					int n = POLLS.merge(ref, 1, Integer::sum);
					if (ref.startsWith("slow")) {
						return n < 3 ? new ProviderResult.Pending(ref, ProviderResult.Mode.POLL, null)
								: new ProviderResult.Done(Map.of("active", true));
					}
					throw new ProviderException("BOOM", "boom", ref.startsWith("flaky"));
				}
			};
		}
	}

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	FlowService flows;

	@Autowired
	ExecutionService executions;

	@Autowired
	ProviderConfigService configs;

	@Autowired
	ProviderCallRepository calls;

	@Autowired
	ProviderCallbackService completion;

	@Autowired
	List<DataProvider> providers;

	@Autowired
	com.prevention.fraud.validationflow.application.provider.ProviderCacheRepository cacheRepo;

	static class TestClock extends Clock {
		Instant now = Instant.now();

		public Instant instant() {
			return now;
		}

		public java.time.ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		public Clock withZone(java.time.ZoneId z) {
			return this;
		}
	}

	private final TestClock clock = new TestClock();

	private final io.micrometer.core.instrument.simple.SimpleMeterRegistry meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

	private final ProviderMetrics metrics = new com.prevention.fraud.validationflow.config.MicrometerProviderMetrics(meters);

	private double polls(String outcome) {
		var c = meters.find("validation.provider.poll").tags("provider", "fake-poll", "outcome", outcome).counter();
		return c == null ? 0 : c.count();
	}

	private ProviderPollService poller() {
		return new ProviderPollService(calls, configs, Map.of("fake-poll", providers.stream()
				.filter(p -> p.key().equals("fake-poll")).findFirst().orElseThrow()), completion, clock,
				Duration.ofSeconds(10), Duration.ofSeconds(60), 2, metrics);
	}

	private FlowExecution start(String tenant, String ref, boolean enabled) {
		return start(tenant, ref, enabled, null);
	}

	private FlowExecution start(String tenant, String ref, boolean enabled, String operation) {
		var nodeConfig = new java.util.HashMap<String, Object>(Map.of("providerKey", "fake-poll", "query", Map.of("id", "doc"),
				"expect", Map.of("active", true), "maxWait", "PT1H"));
		if (operation != null) {
			nodeConfig.put("operation", operation);
		}
		configs.save(new ProviderConfig(tenant, "fake-poll", true, null, null, null, null, null, "t", null));
		Map<String, Object> g = Map.of("startNodeId", "s", "nodes", Map.of(
				"s", Map.of("type", "START", "transitions", List.of(Map.of("to", "x"))),
				"x", Map.of("type", "EXTERNAL_CALL", "config", nodeConfig, "transitions", List.of(Map.of("to", "ok"))),
				"ok", Map.of("type", "END", "config", Map.of("decision", "APPROVED"))));
		String ctx = "P-" + ref;
		var d = flows.createDraft(tenant, "t", new FlowService.CreateFlow("k-" + ctx, "PF", ctx, "d", null, g, List.of(), null));
		flows.activate(tenant, d.id());
		var started = executions.execute(tenant, null, "PF", ctx, Map.of("doc", ref), null);
		clock.now = Instant.now();
		if (!enabled) {
			configs.save(new ProviderConfig(tenant, "fake-poll", false, null, null, null, null, null, "t", null));
		}
		return started;
	}

	private String callStatus(FlowExecution f) {
		return jdbc.queryForObject("SELECT status FROM provider_call WHERE execution_id = ?", String.class, f.id());
	}

	private int attempts(FlowExecution f) {
		return jdbc.queryForObject("SELECT attempts FROM provider_call WHERE execution_id = ?", Integer.class, f.id());
	}

	private String exStatus(FlowExecution f) {
		return jdbc.queryForObject("SELECT status FROM flow_execution WHERE id = ?", String.class, f.id());
	}

	@Test
	void pendingBacksOffExponentiallyThenDoneResumes() {
		var w = start("poll-a", "slow-1", true);
		var poller = poller();
		poller.pollDue();
		assertEquals(0, POLLS.getOrDefault("slow-1", 0), "not due yet");

		clock.now = clock.now.plusSeconds(10);
		poller.pollDue();
		assertEquals("WAITING", exStatus(w));
		// 10s since the last attempt -> next delay 20s
		var row = jdbc.queryForMap("SELECT next_poll_at, updated_at FROM provider_call WHERE execution_id = ?", w.id());
		var next = ((java.sql.Timestamp) row.get("next_poll_at")).toInstant();
		var updated = ((java.sql.Timestamp) row.get("updated_at")).toInstant();
		assertTrue(Duration.between(updated, next).compareTo(Duration.ofSeconds(20)) >= 0
				&& Duration.between(updated, next).compareTo(Duration.ofSeconds(21)) < 0);
		assertEquals(1, POLLS.get("slow-1"));

		clock.now = clock.now.plusSeconds(19);
		poller.pollDue();
		assertEquals(1, POLLS.get("slow-1"), "backoff not elapsed");
		clock.now = clock.now.plusSeconds(2);
		poller.pollDue();
		clock.now = clock.now.plusSeconds(60);
		poller.pollDue();

		assertEquals(3, POLLS.get("slow-1"));
		assertEquals("COMPLETED", exStatus(w));
		assertEquals("COMPLETED", callStatus(w));
		assertTrue(polls("pending") >= 2, "counts include other tests' due rows");
		assertTrue(polls("done") >= 1);
		metrics.gauges(() -> calls.countByStatus(com.prevention.fraud.validationflow.application.provider.ProviderCall.Status.PENDING),
				() -> calls.countByStatus(com.prevention.fraud.validationflow.application.provider.ProviderCall.Status.DEAD));
		assertTrue(meters.get("validation.provider.pending").gauge().value() >= 0);
		assertTrue(meters.get("validation.provider.dead").gauge().value() >= 0);
		clock.now = clock.now.plusSeconds(600);
		poller.pollDue();
		assertEquals(3, POLLS.get("slow-1"), "completed calls are not polled again");
	}

	@Test
	void retryableErrorsRetryUntilMaxAttemptsNonRetryableFailsAtOnce() {
		var flaky = start("poll-a", "flaky-1", true);
		var fatal = start("poll-a", "fatal-1", true);
		var poller = poller();
		clock.now = clock.now.plusSeconds(10);
		poller.pollDue();
		assertEquals("PENDING", callStatus(flaky));
		assertEquals(1, attempts(flaky));
		assertEquals("FAILED", callStatus(fatal));

		clock.now = clock.now.plusSeconds(60);
		poller.pollDue();
		assertEquals("PENDING", callStatus(flaky));
		assertEquals(2, attempts(flaky));
		clock.now = clock.now.plusSeconds(60);
		poller.pollDue();
		assertEquals("FAILED", callStatus(flaky), "attempts above max-attempts");
		assertEquals("WAITING", exStatus(flaky));
	}

	@Test
	void disabledProviderFailsAndTenantsAreIndependent() {
		var off = start("poll-off", "slow-2", false);
		var on = start("poll-on", "slow-3", true);
		var poller = poller();
		clock.now = clock.now.plusSeconds(10);
		poller.pollDue();
		assertEquals("FAILED", callStatus(off));
		assertEquals("PENDING", callStatus(on));
		assertTrue(POLLS.getOrDefault("slow-2", 0) == 0);
	}

	@Test
	void asyncCompletionStoresInCacheWhenPolicyAllows() {
		var f = start("poll-cache", "slow-4", true);
		cacheRepo.savePolicy(new com.prevention.fraud.validationflow.application.provider.ProviderCachePolicy("poll-cache", "fake-poll",
				com.prevention.fraud.validationflow.application.provider.ProviderCache.OPERATION, "LOW_VOLATILITY", 3600, false, null, null,
				null, null), "t");
		var poller = poller();
		for (int i = 0; i < 3; i++) {
			clock.now = clock.now.plusSeconds(120);
			poller.pollDue();
		}
		assertEquals("COMPLETED", exStatus(f));
		assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM provider_cache_entry WHERE tenant_id = 'poll-cache'", Integer.class));
	}

	@Test
	void asyncCompletionStoresUnderTheNodesOperation() {
		var f = start("poll-op", "slow-4", true, "lookup");
		cacheRepo.savePolicy(new com.prevention.fraud.validationflow.application.provider.ProviderCachePolicy("poll-op", "fake-poll",
				"lookup", "LOW_VOLATILITY", 3600, false, null, null, null, null), "t");
		var poller = poller();
		for (int i = 0; i < 3; i++) {
			clock.now = clock.now.plusSeconds(120);
			poller.pollDue();
		}
		assertEquals("COMPLETED", exStatus(f));
		assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM provider_cache_entry WHERE tenant_id = 'poll-op' AND operation = 'lookup'",
				Integer.class));
	}

	@Test
	void completionAfterCrashBetweenResumeAndMarkCompletedFinishesTheBookkeeping() {
		var f = start("poll-crash", "slow-5", true);
		var call = calls.find("poll-crash", jdbc.queryForObject("SELECT id FROM provider_call WHERE execution_id = ?",
				java.util.UUID.class, f.id())).orElseThrow();
		// the execution resumed, then the app died before provider_call was marked COMPLETED
		executions.resumeWithProviderResult("poll-crash", f.id(), call.nodeId(), call.id(), Map.of("active", true), true);
		assertEquals("PENDING", callStatus(f));

		assertEquals(ProviderCallbackService.Outcome.DUPLICATE, completion.complete(call, Map.of("active", true)));
		assertEquals("COMPLETED", callStatus(f));
		assertEquals("COMPLETED", exStatus(f));
	}

}
