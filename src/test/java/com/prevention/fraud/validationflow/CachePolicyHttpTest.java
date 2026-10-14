package com.prevention.fraud.validationflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Cache policy admin API: scope, validation, tenant isolation and audit (real Postgres). */
@SpringBootTest(properties = {
		"app.security.api-keys[0].key=ca", "app.security.api-keys[0].tenant-id=cp-a", "app.security.api-keys[0].name=ops-a",
		"app.security.api-keys[0].scopes=provider:read,provider:admin",
		"app.security.api-keys[1].key=cb", "app.security.api-keys[1].tenant-id=cp-b", "app.security.api-keys[1].scopes=provider:admin",
		"app.security.api-keys[2].key=cro", "app.security.api-keys[2].tenant-id=cp-a", "app.security.api-keys[2].scopes=provider:read"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CachePolicyHttpTest {

	private static final String URL = "/api/v1/providers/brasilapi-cnpj/cache-policies";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	private ResultActions putPolicy(String key, String path, String body) throws Exception {
		return mvc.perform(put(path).header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	@Test
	void writeValidateIsolateAndAudit() throws Exception {
		mvc.perform(get(URL).header("X-API-Key", "cro")).andExpect(status().isForbidden());
		putPolicy("cro", URL + "/lookup", "{\"dataCategory\":\"LOW_VOLATILITY\"}").andExpect(status().isForbidden());
		mvc.perform(get(URL)).andExpect(status().isUnauthorized());

		putPolicy("ca", URL + "/lookup", "{\"dataCategory\":\"MEDIUM_VOLATILITY\",\"fallbackOnErrorAllowed\":true}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.operation").value("lookup"));
		putPolicy("ca", URL + "/lookup", "{\"dataCategory\":\"CUSTOM\",\"ttlSeconds\":60,\"justification\":\"j\",\"owner\":\"o\",\"reviewDate\":\"2027-01-01\"}")
				.andExpect(status().isOk());
		mvc.perform(get(URL).header("X-API-Key", "ca")).andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].dataCategory").value("CUSTOM")).andExpect(jsonPath("$[0].owner").value("o"));
		mvc.perform(get(URL).header("X-API-Key", "cb")).andExpect(jsonPath("$.length()").value(0));

		// invalid: incomplete CUSTOM, unknown category, unknown provider
		putPolicy("ca", URL + "/other", "{\"dataCategory\":\"CUSTOM\",\"ttlSeconds\":60}").andExpect(status().isBadRequest());
		putPolicy("ca", URL + "/other", "{\"dataCategory\":\"FOREVER\"}").andExpect(status().isBadRequest());
		putPolicy("ca", "/api/v1/providers/nope/cache-policies/x", "{\"dataCategory\":\"REAL_TIME\"}").andExpect(status().isNotFound());
		mvc.perform(get(URL).header("X-API-Key", "ca")).andExpect(jsonPath("$.length()").value(1));

		assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM provider_config_audit WHERE tenant_id = 'cp-a' AND actor = 'ops-a' AND event_type LIKE 'CACHE_POLICY_%'", Integer.class));
	}

}
