package com.prevention.fraud.validationflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Provider management API: scopes, validation, tenant isolation, updated_by, audit and cache invalidation (real Postgres). */
@SpringBootTest(properties = {
		"app.security.api-keys[0].key=pa", "app.security.api-keys[0].tenant-id=pa-t", "app.security.api-keys[0].name=ops-a",
		"app.security.api-keys[0].scopes=provider:read,provider:admin",
		"app.security.api-keys[1].key=pb", "app.security.api-keys[1].tenant-id=pb-t",
		"app.security.api-keys[1].scopes=provider:read,provider:admin",
		"app.security.api-keys[2].key=ro", "app.security.api-keys[2].tenant-id=pa-t", "app.security.api-keys[2].scopes=provider:read"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProviderAdminHttpTest {

	private static final String URL = "/api/v1/providers/brasilapi-cnpj";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	com.prevention.fraud.validationflow.application.provider.ProviderConfigService configs;

	private org.springframework.test.web.servlet.ResultActions putCfg(String key, String path, String body) throws Exception {
		return mvc.perform(put(path).header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	@Test
	void putListAuditIsolationAndValidation() throws Exception {
		// scopes: read-only key cannot write; no key is 401
		putCfg("ro", URL, "{\"enabled\":true}").andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/providers")).andExpect(status().isUnauthorized());

		// disabled until configured, then the same process sees the change at once (cache invalidated)
		org.junit.jupiter.api.Assertions.assertTrue(configs.resolve("pa-t", "brasilapi-cnpj").isEmpty());
		putCfg("pa", URL, "{\"enabled\":true,\"baseUrl\":\"https://a.example/api\",\"connectTimeoutMs\":1000,\"credentialsRef\":\"MY_TOKEN\"}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.updatedBy").value("ops-a"))
				.andExpect(jsonPath("$.credentialsRef").value("MY_TOKEN")).andExpect(jsonPath("$.enabled").value(true));
		org.junit.jupiter.api.Assertions.assertTrue(configs.resolve("pa-t", "brasilapi-cnpj").isPresent());
		putCfg("pa", URL, "{\"enabled\":false}").andExpect(status().isOk());
		org.junit.jupiter.api.Assertions.assertTrue(configs.resolve("pa-t", "brasilapi-cnpj").isEmpty());

		// audit: one entry per change, written by the actor
		assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM provider_config_audit WHERE tenant_id = 'pa-t' AND actor = 'ops-a'", Integer.class));
		assertEquals("PROVIDER_CONFIG_CREATED", jdbc.queryForObject(
				"SELECT event_type FROM provider_config_audit WHERE tenant_id = 'pa-t' ORDER BY created_at LIMIT 1", String.class));

		// list is tenant-scoped; tenant B (updated_by falls back to its tenant) sees none of A's rows
		mvc.perform(get("/api/v1/providers").header("X-API-Key", "ro")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
		mvc.perform(get("/api/v1/providers").header("X-API-Key", "pb")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
		putCfg("pb", URL, "{\"enabled\":true}").andExpect(status().isOk()).andExpect(jsonPath("$.updatedBy").value("pb-t"));
		mvc.perform(get("/api/v1/providers").header("X-API-Key", "ro")).andExpect(jsonPath("$[0].enabled").value(false));

		// validation
		putCfg("pa", "/api/v1/providers/nope", "{\"enabled\":true}").andExpect(status().isNotFound());
		putCfg("pa", URL, "{\"enabled\":true,\"baseUrl\":\"http://plain.example\"}").andExpect(status().isBadRequest());
		putCfg("pa", URL, "{\"enabled\":true,\"readTimeoutMs\":0}").andExpect(status().isBadRequest());
	}

}
