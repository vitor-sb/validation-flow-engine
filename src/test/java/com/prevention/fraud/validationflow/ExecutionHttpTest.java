package com.prevention.fraud.validationflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.json.JsonMapper;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

/** HTTP-level tests of the execution read endpoints against a real Postgres (two tenants). */
@SpringBootTest(properties = {
		"app.security.api-keys[0].key=ka", "app.security.api-keys[0].tenant-id=ta",
		"app.security.api-keys[0].scopes=flow:write,flow:activate,validation:read,validation:execute",
		"app.security.api-keys[1].key=kb", "app.security.api-keys[1].tenant-id=tb",
		"app.security.api-keys[1].scopes=flow:write,flow:activate,validation:read,validation:execute"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ExecutionHttpTest {

	private static final String FLOW = """
			{"flowKey":"%s","userType":"PF","context":"HTTP","displayName":"d","inputContract":[],
			 "graphDefinition":{"startNodeId":"s","nodes":{
			   "s":{"type":"START","transitions":[{"to":"e"}]},"e":{"type":"END"}}}}""";

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	@Autowired
	org.springframework.jdbc.core.JdbcTemplate jdbc;

	private String body(org.springframework.test.web.servlet.ResultActions r) throws Exception {
		return r.andReturn().getResponse().getContentAsString();
	}

	private String id(String content) {
		return json.readTree(content).get("id") != null ? json.readTree(content).get("id").asString()
				: json.readTree(content).get("executionId").asString();
	}

	/** Creates + activates a trivial flow for the tenant and runs one execution; returns the execution id. */
	private String runExecution(String key, String flowKey) throws Exception {
		String flowId = id(body(mvc.perform(post("/api/v1/flows").header("X-API-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(FLOW.formatted(flowKey))).andExpect(status().isCreated())));
		mvc.perform(patch("/api/v1/flows/" + flowId + "/activate").header("X-API-Key", key)).andExpect(status().isOk());
		return id(body(mvc.perform(post("/api/v1/executions").header("X-API-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"flowKey\":\"" + flowKey + "\",\"userType\":\"PF\",\"context\":\"HTTP\",\"inputData\":{}}"))
				.andExpect(status().isCreated())));
	}

	@Test
	void getByIdAndNodesSucceed() throws Exception {
		String exec = runExecution("ka", "get-ok");
		mvc.perform(get("/api/v1/executions/" + exec).header("X-API-Key", "ka")).andExpect(status().isOk())
				.andExpect(jsonPath("$.executionId").value(exec)).andExpect(jsonPath("$.flowKey").value("get-ok"))
				.andExpect(jsonPath("$.status").value("COMPLETED"));
		mvc.perform(get("/api/v1/executions/" + exec + "/nodes").header("X-API-Key", "ka")).andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(2))).andExpect(jsonPath("$[*].nodeId").value(hasItem("e")));
	}

	@Test
	void unknownIdIs404ExecutionNotFound() throws Exception {
		String missing = java.util.UUID.randomUUID().toString();
		for (String path : new String[] { "", "/nodes" }) {
			mvc.perform(get("/api/v1/executions/" + missing + path).header("X-API-Key", "ka"))
					.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("EXECUTION_NOT_FOUND"));
		}
	}

	@Test
	void otherTenantsExecutionIs404AndListExcludesIt() throws Exception {
		String exec = runExecution("ka", "iso");
		for (String path : new String[] { "", "/nodes" }) {
			mvc.perform(get("/api/v1/executions/" + exec + path).header("X-API-Key", "kb"))
					.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("EXECUTION_NOT_FOUND"));
		}
		mvc.perform(get("/api/v1/executions?size=100").header("X-API-Key", "kb")).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[*].executionId").value(not(hasItem(exec))));
		mvc.perform(get("/api/v1/executions?size=100").header("X-API-Key", "ka")).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[*].executionId").value(hasItem(exec)));
	}

	@Test
	void listPaginatesAndCapsSize() throws Exception {
		for (int i = 0; i < 3; i++) {
			runExecution("kb", "page" + i);
		}
		mvc.perform(get("/api/v1/executions?page=0&size=2").header("X-API-Key", "kb")).andExpect(status().isOk())
				.andExpect(jsonPath("$.items", hasSize(2))).andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(2)).andExpect(jsonPath("$.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)));
		mvc.perform(get("/api/v1/executions?page=1&size=2").header("X-API-Key", "kb")).andExpect(status().isOk())
				.andExpect(jsonPath("$.items", hasSize(org.hamcrest.Matchers.greaterThanOrEqualTo(1))))
				.andExpect(jsonPath("$.page").value(1));
		mvc.perform(get("/api/v1/executions?size=101").header("X-API-Key", "kb")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("BAD_REQUEST"));
		mvc.perform(get("/api/v1/executions?size=0").header("X-API-Key", "kb")).andExpect(status().isBadRequest());
	}

	private org.springframework.test.web.servlet.ResultActions startIdem(String apiKey, String idemKey, String flowKey,
			String input) throws Exception {
		return mvc.perform(post("/api/v1/executions").header("X-API-Key", apiKey).header("Idempotency-Key", idemKey)
				.contentType(MediaType.APPLICATION_JSON).content("{\"flowKey\":\"" + flowKey
						+ "\",\"userType\":\"PF\",\"context\":\"HTTP\",\"inputData\":" + input + "}"));
	}

	private void activateFlow(String apiKey, String flowKey) throws Exception {
		String flowId = id(body(mvc.perform(post("/api/v1/flows").header("X-API-Key", apiKey)
				.contentType(MediaType.APPLICATION_JSON).content(FLOW.formatted(flowKey))).andExpect(status().isCreated())));
		mvc.perform(patch("/api/v1/flows/" + flowId + "/activate").header("X-API-Key", apiKey)).andExpect(status().isOk());
	}

	@Test
	void invalidIdempotencyKeyIs400AndCreatesNothing() throws Exception {
		activateFlow("ka", "idemval");
		String before = body(mvc.perform(get("/api/v1/executions").header("X-API-Key", "ka"))).replaceAll(".*\"total\":(\\d+).*", "$1");
		for (String bad : new String[] { "k".repeat(256), "bad key!" }) {
			startIdem("ka", bad, "idemval", "{}").andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("BAD_REQUEST"));
		}
		mvc.perform(get("/api/v1/executions").header("X-API-Key", "ka")).andExpect(jsonPath("$.total").value(Integer.parseInt(before)));
		startIdem("ka", "k".repeat(255), "idemval", "{}").andExpect(status().isCreated());
	}

	@Test
	void idempotencyKeyReplaysSamePayloadAndRejectsDifferentOne() throws Exception {
		activateFlow("ka", "idem");
		String first = id(body(startIdem("ka", "k-1", "idem", "{\"a\":1,\"b\":2}").andExpect(status().isCreated())
				.andExpect(header().doesNotExist("Idempotent-Replayed"))));
		// same payload (different key order) -> original execution, flagged as replay
		String again = id(body(startIdem("ka", "k-1", "idem", "{\"b\":2,\"a\":1}").andExpect(status().isCreated())
				.andExpect(header().string("Idempotent-Replayed", "true"))));
		org.junit.jupiter.api.Assertions.assertEquals(first, again);
		startIdem("ka", "k-1", "idem", "{\"a\":9}").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CONFLICT"));
		// key scope is the tenant: tb can use "k-1" for its own flow
		activateFlow("kb", "idem");
		String other = id(body(startIdem("kb", "k-1", "idem", "{\"a\":1,\"b\":2}").andExpect(status().isCreated())
				.andExpect(header().doesNotExist("Idempotent-Replayed"))));
		org.junit.jupiter.api.Assertions.assertNotEquals(first, other);
		// no Idempotency-Key -> never flagged
		mvc.perform(post("/api/v1/executions").header("X-API-Key", "ka").contentType(MediaType.APPLICATION_JSON)
				.content("{\"flowKey\":\"idem\",\"userType\":\"PF\",\"context\":\"HTTP\",\"inputData\":{}}"))
				.andExpect(status().isCreated()).andExpect(header().doesNotExist("Idempotent-Replayed"));
	}

	@Test
	void concurrentRequestsWithSameKeyCreateOneExecution() throws Exception {
		activateFlow("ka", "idem-conc");
		var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
		try {
			var futures = new java.util.ArrayList<java.util.concurrent.Future<String>>();
			for (int i = 0; i < 8; i++) {
				futures.add(pool.submit(() -> id(body(startIdem("ka", "k-conc", "idem-conc", "{}")
						.andExpect(status().isCreated())))));
			}
			var ids = new java.util.HashSet<String>();
			for (var f : futures) {
				ids.add(f.get());
			}
			org.junit.jupiter.api.Assertions.assertEquals(1, ids.size());
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void rejectedRequestReleasesTheKey() throws Exception {
		// no active flow -> 404; the key must stay reusable afterwards
		startIdem("ka", "k-rel", "idem-late", "{}").andExpect(status().isNotFound());
		activateFlow("ka", "idem-late");
		startIdem("ka", "k-rel", "idem-late", "{}").andExpect(status().isCreated());
	}

	@Test
	void expiredOrphanReservationIsTakenOverButLiveOneIsNot() throws Exception {
		activateFlow("ka", "lease");
		jdbc.update("INSERT INTO idempotency_key (tenant_id, idempotency_key, request_hash, locked_until) "
				+ "VALUES ('ta', 'orphan', 'x', now() - interval '1 minute'), "
				+ "('ta', 'live', 'x', now() + interval '1 hour')");
		startIdem("ka", "orphan", "lease", "{}").andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("COMPLETED"));
		org.junit.jupiter.api.Assertions.assertNotNull(jdbc.queryForObject(
				"SELECT execution_id FROM idempotency_key WHERE tenant_id='ta' AND idempotency_key='orphan'", java.util.UUID.class));
		// live reservation (hash differs, so it answers 409 at once) stays untouched
		startIdem("ka", "live", "lease", "{}").andExpect(status().isConflict());
		org.junit.jupiter.api.Assertions.assertEquals("x", jdbc.queryForObject(
				"SELECT request_hash FROM idempotency_key WHERE tenant_id='ta' AND idempotency_key='live'", String.class));
	}

}
