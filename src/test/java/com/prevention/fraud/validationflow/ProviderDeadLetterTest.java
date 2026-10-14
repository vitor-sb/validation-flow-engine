package com.prevention.fraud.validationflow;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.prevention.fraud.validationflow.application.execution.ExecutionService;
import com.prevention.fraud.validationflow.application.execution.ProviderCallAdminService;
import com.prevention.fraud.validationflow.application.execution.ProviderCallbackService;
import com.prevention.fraud.validationflow.application.execution.ports.ExecutionRepository;
import com.prevention.fraud.validationflow.application.execution.recovery.WaitExpiryService;
import com.prevention.fraud.validationflow.application.flow.FlowException;
import com.prevention.fraud.validationflow.application.flow.FlowService;
import com.prevention.fraud.validationflow.application.provider.DataProvider;
import com.prevention.fraud.validationflow.application.provider.ProviderCall;
import com.prevention.fraud.validationflow.application.provider.ProviderConfig;
import com.prevention.fraud.validationflow.application.provider.ProviderConfigService;
import com.prevention.fraud.validationflow.application.provider.ProviderResult;
import com.prevention.fraud.validationflow.config.MicrometerExecutionMetrics;
import com.prevention.fraud.validationflow.domain.execution.FlowExecution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Dead letter: FAILED/expired/cancelled calls become DEAD with a reason, listing, retry and tenant isolation. */
@SpringBootTest(properties = {
		"app.security.api-keys[0].key=dl-a", "app.security.api-keys[0].tenant-id=dl-ta",
		"app.security.api-keys[0].scopes=provider:admin",
		"app.security.api-keys[1].key=dl-b", "app.security.api-keys[1].tenant-id=dl-tb",
		"app.security.api-keys[1].scopes=provider:admin",
		"app.security.api-keys[3].key=dl-c", "app.security.api-keys[3].tenant-id=dl-tc",
		"app.security.api-keys[3].scopes=provider:admin",
		"app.security.api-keys[2].key=dl-ro", "app.security.api-keys[2].tenant-id=dl-ta",
		"app.security.api-keys[2].scopes=provider:read" })
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, ProviderDeadLetterTest.Fake.class })
class ProviderDeadLetterTest {

	@TestConfiguration(proxyBeanMethods = false)
	static class Fake {
		@Bean
		DataProvider fakeDeadProvider() {
			return new DataProvider() {
				public String key() {
					return "fake-dl";
				}

				public Map<String, Object> fetch(ProviderConfig c, Map<String, Object> p) {
					return Map.of();
				}

				public ProviderResult start(ProviderConfig c, Map<String, Object> p) {
					return new ProviderResult.Pending("ref", ProviderResult.Mode.POLL, Duration.ofSeconds(10));
				}
			};
		}
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	FlowService flows;

	@Autowired
	ExecutionService executions;

	@Autowired
	ProviderConfigService configs;

	@Autowired
	ProviderCallAdminService admin;

	@Autowired
	ProviderCallbackService callbacks;

	@Autowired
	ExecutionRepository exRepo;

	private FlowExecution waiting(String tenant, String ctx) {
		configs.save(new ProviderConfig(tenant, "fake-dl", true, null, null, null, null, null, "t", null));
		Map<String, Object> g = Map.of("startNodeId", "s", "nodes", Map.of(
				"s", Map.of("type", "START", "transitions", List.of(Map.of("to", "x"))),
				"x", Map.of("type", "EXTERNAL_CALL", "config", Map.of("providerKey", "fake-dl", "query", Map.of("id", "doc"),
						"maxWait", "PT1H"), "transitions", List.of(Map.of("to", "ok"))),
				"ok", Map.of("type", "END", "config", Map.of("decision", "APPROVED"))));
		var d = flows.createDraft(tenant, "t", new FlowService.CreateFlow("k-" + ctx, "DL", ctx, "d", null, g, List.of(), null));
		flows.activate(tenant, d.id());
		return executions.execute(tenant, null, "DL", ctx, Map.of("doc", "1"), null);
	}

	private UUID callOf(FlowExecution f) {
		return jdbc.queryForObject("SELECT id FROM provider_call WHERE execution_id = ?", UUID.class, f.id());
	}

	private Map<String, Object> row(UUID id) {
		return jdbc.queryForMap("SELECT status, reason, attempts, next_poll_at FROM provider_call WHERE id = ?", id);
	}

	@Test
	void failedBecomesDeadListedPerTenantAndRetryPutsItBack() throws Exception {
		var f = waiting("dl-ta", "failed");
		var other = waiting("dl-tb", "failed");
		UUID id = callOf(f);
		jdbc.update("UPDATE provider_call SET status = 'FAILED', reason = 'RETRIES_EXHAUSTED', attempts = 6 WHERE id = ?", id);

		admin.sweep();
		assertEquals("DEAD", row(id).get("status"));
		assertEquals("RETRIES_EXHAUSTED", row(id).get("reason"));
		assertEquals("PENDING", row(callOf(other)).get("status"), "other tenant's call is untouched");

		mvc.perform(get("/api/v1/provider-calls?status=DEAD").header("X-API-Key", "dl-a")).andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value(id.toString()))
			.andExpect(jsonPath("$.items[0].reason").value("RETRIES_EXHAUSTED"))
			.andExpect(jsonPath("$.items[0].callbackTokenHash").doesNotExist());
		mvc.perform(get("/api/v1/provider-calls?status=DEAD").header("X-API-Key", "dl-b")).andExpect(jsonPath("$.total").value(0));
		mvc.perform(get("/api/v1/provider-calls?status=DEAD").header("X-API-Key", "dl-ro")).andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/provider-calls?status=BOGUS").header("X-API-Key", "dl-a")).andExpect(status().isBadRequest());

		mvc.perform(post("/api/v1/provider-calls/" + id + "/retry").header("X-API-Key", "dl-b")).andExpect(status().isNotFound());
		mvc.perform(post("/api/v1/provider-calls/" + id + "/retry").header("X-API-Key", "dl-a")).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.attempts").value(0));
		assertEquals("PENDING", row(id).get("status"));
		assertEquals(0, row(id).get("attempts"));
		assertEquals(false, row(id).get("next_poll_at") == null, "POLL call is due again");
		mvc.perform(post("/api/v1/provider-calls/" + id + "/retry").header("X-API-Key", "dl-a")).andExpect(status().isConflict());
	}

	@Test
	void cancelledExecutionKillsItsCallAndLateCallbackIs409() throws Exception {
		var f = waiting("dl-tc", "cancelled");
		UUID id = callOf(f);
		jdbc.update("UPDATE provider_call SET callback_token_hash = ? WHERE id = ?", ProviderCall.hashToken("tok"), id);
		executions.cancel("dl-tc", f.id(), "no longer needed", "ops");

		admin.sweep();
		assertEquals("DEAD", row(id).get("status"));
		assertEquals("EXECUTION_CANCELLED", row(id).get("reason"));
		assertEquals(null, row(id).get("next_poll_at"), "no longer polled");
		mvc.perform(post("/api/v1/provider-calls/" + id + "/retry").header("X-API-Key", "dl-c")).andExpect(status().isConflict());
		assertThrows(FlowException.class, () -> callbacks.accept(id, "tok", null, new byte[0], Map.of("active", true)));
	}

	@Test
	void expiredExecutionKillsItsCall() {
		var f = waiting("dl-tc", "expired");
		UUID id = callOf(f);
		new WaitExpiryService(exRepo, Clock.fixed(Instant.now().plusSeconds(7200), ZoneOffset.UTC),
				new MicrometerExecutionMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry())).expire();

		admin.sweep();
		assertEquals("DEAD", row(id).get("status"));
		assertEquals("WAIT_EXPIRED", row(id).get("reason"));
	}

}
