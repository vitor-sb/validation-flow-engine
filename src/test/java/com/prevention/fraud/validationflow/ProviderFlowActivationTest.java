package com.prevention.fraud.validationflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A flow whose node uses a provider-backed validator: blocked at activation / warned at validate while the provider is disabled. */
@SpringBootTest(properties = {
		"app.security.api-keys[0].key=fa", "app.security.api-keys[0].tenant-id=fa-t",
		"app.security.api-keys[0].scopes=flow:write,flow:activate,provider:admin"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProviderFlowActivationTest {

	private static final String GRAPH = """
			{"startNodeId":"s","nodes":{"s":{"type":"START","transitions":[{"to":"v"}]},
			"v":{"type":"VALIDATION","config":{"validatorType":"brasilapi-cnpj","params":{"query":{"cnpj":"companyCnpj"}}},"transitions":[{"to":"e"}]},
			"e":{"type":"END"}}}""";

	@Autowired
	MockMvc mvc;

	@Test
	void disabledProviderBlocksActivationUntilEnabled() throws Exception {
		mvc.perform(post("/api/v1/flows/validate").header("X-API-Key", "fa").contentType(MediaType.APPLICATION_JSON)
				.content("{\"graphDefinition\":" + GRAPH + "}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true))
				.andExpect(jsonPath("$.warnings[0].code").value("PROVIDER_DISABLED"))
				.andExpect(jsonPath("$.warnings[0].nodeId").value("v"));

		String body = mvc.perform(post("/api/v1/flows").header("X-API-Key", "fa").contentType(MediaType.APPLICATION_JSON)
				.content("{\"flowKey\":\"pf\",\"userType\":\"PJ\",\"context\":\"KYB\",\"displayName\":\"d\",\"graphDefinition\":" + GRAPH + "}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String id = com.jayway.jsonpath.JsonPath.read(body, "$.id");

		mvc.perform(patch("/api/v1/flows/" + id + "/activate").header("X-API-Key", "fa"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("PROVIDER_DISABLED")));

		mvc.perform(put("/api/v1/providers/brasilapi-cnpj").header("X-API-Key", "fa").contentType(MediaType.APPLICATION_JSON)
				.content("{\"enabled\":true}")).andExpect(status().isOk());

		mvc.perform(patch("/api/v1/flows/" + id + "/activate").header("X-API-Key", "fa")).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACTIVE"));
	}

}
