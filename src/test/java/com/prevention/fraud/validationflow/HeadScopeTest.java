package com.prevention.fraud.validationflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
		"spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
				+ "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
				+ "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
		"app.security.api-keys[0].key=", "app.security.api-keys[0].tenant-id=blank",
		"app.security.api-keys[0].scopes=flow:read,validation:read",
		"app.security.api-keys[1].key=exec-only", "app.security.api-keys[1].tenant-id=t1",
		"app.security.api-keys[1].scopes=validation:execute"
})
@AutoConfigureMockMvc
class HeadScopeTest {

	@MockitoBean
	com.prevention.fraud.validationflow.application.FlowRepository repository;

	@MockitoBean
	com.prevention.fraud.validationflow.application.ExecutionRepository executionRepository;

	@Autowired
	MockMvc mvc;

	// HEAD is served by the GET handlers, so scope rules must cover it too
	@Test
	void headRequiresTheSameScopeAsGet() throws Exception {
		mvc.perform(head("/api/v1/executions/" + java.util.UUID.randomUUID()).header("X-API-Key", "exec-only"))
				.andExpect(status().isForbidden());
		mvc.perform(head("/api/v1/flows/" + java.util.UUID.randomUUID()).header("X-API-Key", "exec-only"))
				.andExpect(status().isForbidden());
		mvc.perform(head("/api/v1/node-types").header("X-API-Key", "exec-only")).andExpect(status().isForbidden());
	}

}
