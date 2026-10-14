package com.prevention.fraud.validationflow;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.prevention.fraud.validationflow.application.execution.ExecutionService;
import com.prevention.fraud.validationflow.application.flow.FlowService;
import com.prevention.fraud.validationflow.application.provider.CallbackHandle;
import com.prevention.fraud.validationflow.application.provider.CredentialResolver;
import com.prevention.fraud.validationflow.application.provider.DataProvider;
import com.prevention.fraud.validationflow.application.provider.ProviderConfig;
import com.prevention.fraud.validationflow.application.provider.ProviderConfigService;
import com.prevention.fraud.validationflow.application.provider.ProviderResult;
import com.prevention.fraud.validationflow.domain.execution.ExecutionStatus;
import com.prevention.fraud.validationflow.domain.execution.FlowExecution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Provider callbacks over HTTP with a fake provider: token, signature, idempotency, expiry and tenant isolation. */
@SpringBootTest(properties = "app.limits.max-body-bytes=2048")
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, ProviderCallbackHttpTest.Fake.class })
class ProviderCallbackHttpTest {

	static final AtomicReference<CallbackHandle> LAST = new AtomicReference<>();

	@TestConfiguration(proxyBeanMethods = false)
	static class Fake {
		@Bean
		DataProvider fakeCallbackProvider() {
			return new DataProvider() {
				public String key() {
					return "fake-cb";
				}

				public Map<String, Object> fetch(ProviderConfig c, Map<String, Object> p) {
					return Map.of();
				}

				public ProviderResult start(ProviderConfig c, Map<String, Object> p, CallbackHandle cb) {
					LAST.set(cb);
					return new ProviderResult.Pending("ext", ProviderResult.Mode.CALLBACK, null);
				}

				public Map<String, Object> normalizeCallback(ProviderConfig c, Map<String, Object> body) {
					return Map.of("active", "ATIVA".equals(body.get("situacao")));
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

	@MockitoBean
	CredentialResolver credentials;

	private FlowExecution startCall(String tenant, String ctx, String secretRef) {
		configs.save(new ProviderConfig(tenant, "fake-cb", true, null, null, null, null, secretRef, "t", null));
		Map<String, Object> g = Map.of("startNodeId", "s", "nodes", Map.of(
				"s", Map.of("type", "START", "transitions", List.of(Map.of("to", "x"))),
				"x", Map.of("type", "EXTERNAL_CALL", "config", Map.of("providerKey", "fake-cb", "query", Map.of("id", "doc"),
						"expect", Map.of("active", true), "maxWait", "PT1H"), "transitions", List.of(
								Map.of("to", "ok", "condition", Map.of("field", "nodes.x.success", "operator", "EQUALS", "value", true)),
								Map.of("to", "no"))),
				"ok", Map.of("type", "END", "config", Map.of("decision", "APPROVED")),
				"no", Map.of("type", "END", "config", Map.of("decision", "DENIED"))));
		var d = flows.createDraft(tenant, "t", new FlowService.CreateFlow("k-" + ctx, "PF", ctx, "d", null, g, List.of(), null));
		flows.activate(tenant, d.id());
		var waiting = executions.execute(tenant, null, "PF", ctx, Map.of("doc", "d-1"), null);
		assertEquals(ExecutionStatus.WAITING, waiting.status());
		return waiting;
	}

	private MockHttpServletRequestBuilder cb(CallbackHandle h, String token, String body) {
		return post("/api/v1/provider-callbacks/" + h.callId()).header("X-Callback-Token", token)
				.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private String decision(FlowExecution f) {
		return jdbc.queryForObject("SELECT result->'config'->>'decision' FROM flow_execution WHERE id = ?", String.class, f.id());
	}

	private String exStatus(FlowExecution f) {
		return jdbc.queryForObject("SELECT status FROM flow_execution WHERE id = ?", String.class, f.id());
	}

	private String callStatus(CallbackHandle h) {
		return jdbc.queryForObject("SELECT status FROM provider_call WHERE id = ?", String.class, h.callId());
	}

	@Test
	void correctCallbackResumesAndDuplicateIsIdempotent() throws Exception {
		var w = startCall("cb-a", "C1", null);
		var h = LAST.get();
		mvc.perform(cb(h, h.token(), "{\"situacao\":\"ATIVA\"}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACCEPTED"));
		assertEquals("COMPLETED", exStatus(w));
		assertEquals("APPROVED", decision(w));
		assertEquals("COMPLETED", callStatus(h));
		assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM node_execution WHERE execution_id = ? AND node_id = 'x' AND status = 'COMPLETED'",
				Integer.class, w.id()));

		mvc.perform(cb(h, h.token(), "{\"situacao\":\"INATIVA\"}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("DUPLICATE"));
		assertEquals("APPROVED", decision(w));
	}

	@Test
	void wrongTokenIs401AndHidesWhetherTheCallExists() throws Exception {
		var w = startCall("cb-a", "C2", null);
		var h = LAST.get();
		mvc.perform(cb(h, "nope", "{}")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/provider-callbacks/" + h.callId()).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isUnauthorized());
		mvc.perform(cb(new CallbackHandle(java.util.UUID.randomUUID(), "x"), "nope", "{}")).andExpect(status().isUnauthorized());
		assertEquals("WAITING", exStatus(w));

		// another tenant's call: its token does not open this one
		var other = startCall("cb-b", "C2", null);
		var oh = LAST.get();
		mvc.perform(cb(h, oh.token(), "{}")).andExpect(status().isUnauthorized());
		assertEquals("WAITING", exStatus(other));
		assertEquals("PENDING", callStatus(h));
	}

	@Test
	void signatureIsRequiredWhenASecretIsConfigured() throws Exception {
		when(credentials.resolve("CB_SECRET")).thenReturn("s3cret");
		var w = startCall("cb-a", "C3", "CB_SECRET");
		var h = LAST.get();
		String body = "{\"situacao\":\"ATIVA\"}";
		mvc.perform(cb(h, h.token(), body)).andExpect(status().isUnauthorized());
		mvc.perform(cb(h, h.token(), body).header("X-Signature", "00")).andExpect(status().isUnauthorized());
		assertEquals("WAITING", exStatus(w));
		var mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec("s3cret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		String sig = HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
		mvc.perform(cb(h, h.token(), body).header("X-Signature", sig)).andExpect(status().isOk());
		assertEquals("APPROVED", decision(w));
	}

	@Test
	void callbackAfterExpiryCancelOrDeadIs409() throws Exception {
		// deadline passed before the sweeper ran
		var w = startCall("cb-a", "C4", null);
		var h = LAST.get();
		jdbc.update("UPDATE provider_call SET deadline = now() - interval '1 minute' WHERE id = ?", h.callId());
		mvc.perform(cb(h, h.token(), "{\"situacao\":\"ATIVA\"}")).andExpect(status().isConflict());
		assertEquals("WAITING", exStatus(w));
		assertEquals("FAILED", callStatus(h));
		mvc.perform(cb(h, h.token(), "{\"situacao\":\"ATIVA\"}")).andExpect(status().isConflict());

		// execution cancelled while the call was in flight
		var w2 = startCall("cb-a", "C5", null);
		var h2 = LAST.get();
		executions.cancel("cb-a", w2.id(), "x", "t");
		mvc.perform(cb(h2, h2.token(), "{\"situacao\":\"ATIVA\"}")).andExpect(status().isConflict());
		assertEquals("CANCELLED", exStatus(w2));
		assertEquals("FAILED", callStatus(h2));

		// DEAD
		startCall("cb-a", "C6", null);
		var h3 = LAST.get();
		jdbc.update("UPDATE provider_call SET status = 'DEAD' WHERE id = ?", h3.callId());
		mvc.perform(cb(h3, h3.token(), "{\"situacao\":\"ATIVA\"}")).andExpect(status().isConflict());
	}

	@Test
	void oversizedBodyIs413() throws Exception {
		startCall("cb-a", "C7", null);
		var h = LAST.get();
		mvc.perform(cb(h, h.token(), "{\"x\":\"" + "a".repeat(3000) + "\"}")).andExpect(status().isPayloadTooLarge());
	}

}
