package com.prevention.fraud.validationflow;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Proof that tenants sharing a flowKey never see, change or run each other's flows (real Postgres). */
@SpringBootTest(properties = {
		"app.security.api-keys[0].key=ka", "app.security.api-keys[0].tenant-id=mt-a",
		"app.security.api-keys[0].scopes=flow:write,flow:read,flow:activate,validation:read,validation:execute",
		"app.security.api-keys[1].key=kb", "app.security.api-keys[1].tenant-id=mt-b",
		"app.security.api-keys[1].scopes=flow:write,flow:read,flow:activate,validation:read,validation:execute"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MultiTenancyTest {

	private static final String FLOW = """
			{"flowKey":"%s","userType":"PF","context":"%s","displayName":"%s","inputContract":[],
			 "graphDefinition":{"startNodeId":"s","nodes":{
			   "s":{"type":"START","transitions":[{"to":"e"}]},"e":{"type":"END"}}}}""";

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	@Autowired
	JdbcTemplate jdbc;

	private String field(ResultActions r, String name) throws Exception {
		return json.readTree(r.andReturn().getResponse().getContentAsString()).get(name).asString();
	}

	private String createFlow(String key, String flowKey, String ctx, String name) throws Exception {
		return field(mvc.perform(post("/api/v1/flows").header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON)
				.content(FLOW.formatted(flowKey, ctx, name))).andExpect(status().isCreated()), "id");
	}

	private ResultActions execute(String key, String flowKey, String input) throws Exception {
		return mvc.perform(post("/api/v1/executions").header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON)
				.content("{\"flowKey\":\"" + flowKey + "\",\"userType\":\"PF\",\"context\":\"" + flowKey + "\",\"inputData\":" + input + "}"));
	}

	@Test
	void sameFlowKeyYieldsIndependentFlowsAndExecutionsPerTenant() throws Exception {
		// tenant A reaches version 2 (activating v2 archives v1); tenant B only has version 1
		String a1 = createFlow("ka", "shared", "shared", "A1");
		mvc.perform(patch("/api/v1/flows/" + a1 + "/activate").header("X-API-Key", "ka")).andExpect(status().isOk());
		String a2 = createFlow("ka", "shared", "shared", "A2");
		mvc.perform(patch("/api/v1/flows/" + a2 + "/activate").header("X-API-Key", "ka")).andExpect(status().isOk());
		String b1 = createFlow("kb", "shared", "shared", "B1");
		mvc.perform(patch("/api/v1/flows/" + b1 + "/activate").header("X-API-Key", "kb")).andExpect(status().isOk());

		String execA = field(execute("ka", "shared", "{\"doc\":\"from-a\"}").andExpect(status().isCreated())
				.andExpect(jsonPath("$.flowVersion").value(2)), "executionId");
		String execB = field(execute("kb", "shared", "{\"doc\":\"from-b\"}").andExpect(status().isCreated())
				.andExpect(jsonPath("$.flowVersion").value(1)), "executionId");

		// persisted record carries tenantId, flowVersion and the context data actually used
		Map<String, Object> rowA = jdbc.queryForMap(
				"SELECT tenant_id, flow_version, context_data::text AS ctx FROM flow_execution WHERE id = ?::uuid", execA);
		assertEquals("mt-a", rowA.get("tenant_id"));
		assertEquals(2, rowA.get("flow_version"));
		assertTrue(rowA.get("ctx").toString().contains("from-a") && !rowA.get("ctx").toString().contains("from-b"));
		Map<String, Object> rowB = jdbc.queryForMap(
				"SELECT tenant_id, flow_version, context_data::text AS ctx FROM flow_execution WHERE id = ?::uuid", execB);
		assertEquals("mt-b", rowB.get("tenant_id"));
		assertEquals(1, rowB.get("flow_version"));
		assertTrue(rowB.get("ctx").toString().contains("from-b"));
		// transition audit rows keep the tenant too
		assertTrue(jdbc.queryForObject(
				"SELECT count(*) FROM execution_audit_log WHERE execution_id = ?::uuid AND tenant_id = 'mt-a'",
				Integer.class, execA) > 0);

		// listing is tenant-scoped even with the same flowKey
		mvc.perform(get("/api/v1/flows?flowKey=shared&size=100").header("X-API-Key", "kb")).andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value(b1));
		mvc.perform(get("/api/v1/flows?flowKey=shared&size=100").header("X-API-Key", "ka")).andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(2));
	}

	@Test
	void tenantCannotReadActivateArchiveUpdateOrExecuteOtherTenantsFlow() throws Exception {
		String draft = createFlow("ka", "iso-draft", "iso-draft", "A-draft");
				String other = createFlow("ka", "only-a", "only-a", "A-only");
		mvc.perform(patch("/api/v1/flows/" + other + "/activate").header("X-API-Key", "ka")).andExpect(status().isOk());

		mvc.perform(get("/api/v1/flows/" + draft).header("X-API-Key", "kb")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("FLOW_NOT_FOUND"));
		mvc.perform(patch("/api/v1/flows/" + draft + "/activate").header("X-API-Key", "kb"))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FLOW_NOT_FOUND"));
		mvc.perform(patch("/api/v1/flows/" + other + "/archive").header("X-API-Key", "kb"))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FLOW_NOT_FOUND"));
		mvc.perform(put("/api/v1/flows/" + draft).header("X-API-Key", "kb").contentType(MediaType.APPLICATION_JSON)
				.content(FLOW.formatted("iso-draft", "iso-draft", "hijack"))).andExpect(status().isNotFound());
		mvc.perform(post("/api/v1/executions").header("X-API-Key", "kb").contentType(MediaType.APPLICATION_JSON)
				.content("{\"flowKey\":\"only-a\",\"userType\":\"PF\",\"context\":\"only-a\",\"inputData\":{}}"))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FLOW_NOT_FOUND"));

		// nothing changed for the owner
		mvc.perform(get("/api/v1/flows/" + draft).header("X-API-Key", "ka")).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("DRAFT")).andExpect(jsonPath("$.displayName").value("A-draft"));
		mvc.perform(get("/api/v1/flows/" + other).header("X-API-Key", "ka")).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACTIVE"));
	}
}
