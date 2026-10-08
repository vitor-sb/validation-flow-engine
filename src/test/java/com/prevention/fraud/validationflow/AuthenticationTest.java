package com.prevention.fraud.validationflow;

import com.prevention.fraud.validationflow.application.execution.ports.ExecutionRepository;
import com.prevention.fraud.validationflow.application.flow.ports.FlowRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
		"spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
				+ "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
				+ "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
		"app.security.api-keys[0].key=exec-key", "app.security.api-keys[0].tenant-id=t1",
		"app.security.api-keys[0].scopes=validation:execute",
		"app.security.api-keys[1].key=admin-key", "app.security.api-keys[1].tenant-id=t1",
		"app.security.api-keys[1].scopes=flow:write,flow:activate"
})
@AutoConfigureMockMvc
class AuthenticationTest {

	@org.springframework.test.context.bean.override.mockito.MockitoBean
	com.prevention.fraud.validationflow.application.flow.ports.FlowRepository repository;

	@org.springframework.test.context.bean.override.mockito.MockitoBean
	com.prevention.fraud.validationflow.application.execution.ports.ExecutionRepository executionRepository;

	@Autowired
	MockMvc mvc;

	@Test
	void missingOrInvalidKeyIs401() throws Exception {
		mvc.perform(post("/api/v1/flows")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/flows").header("X-API-Key", "nope")).andExpect(status().isUnauthorized());
	}

	@Test
	void executeOnlyScopeIs403OnFlowWriteAndActivate() throws Exception {
		mvc.perform(post("/api/v1/flows").header("X-API-Key", "exec-key")).andExpect(status().isForbidden());
		mvc.perform(patch("/api/v1/flows/1/activate").header("X-API-Key", "exec-key"))
				.andExpect(status().isForbidden());
	}

	@Test
	void sufficientScopePassesAuthorization() throws Exception {
		// No controller exists yet, so passing authorization surfaces as 404/405 rather than 401/403.
		mvc.perform(post("/api/v1/flows").header("X-API-Key", "admin-key")).andExpect(status().is4xxClientError())
				.andExpect(result -> {
					int s = result.getResponse().getStatus();
					if (s == 401 || s == 403) {
						throw new AssertionError("unexpected " + s);
					}
				});
	}

}
