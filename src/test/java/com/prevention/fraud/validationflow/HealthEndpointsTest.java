package com.prevention.fraud.validationflow;

import com.prevention.fraud.validationflow.application.execution.ports.ExecutionRepository;
import com.prevention.fraud.validationflow.application.flow.ports.FlowRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// DB auto-config is excluded: these endpoints must not need Postgres (Testcontainers tests arrive in US-002).
@SpringBootTest(properties = {
		"spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
				+ "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
				+ "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration,"
				+ "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
})
@AutoConfigureMockMvc
class HealthEndpointsTest {

	@org.springframework.test.context.bean.override.mockito.MockitoBean
	com.prevention.fraud.validationflow.application.flow.ports.FlowRepository repository;

	@org.springframework.test.context.bean.override.mockito.MockitoBean
	com.prevention.fraud.validationflow.application.execution.ports.ExecutionRepository executionRepository;

	@org.springframework.test.context.bean.override.mockito.MockitoBean
	com.prevention.fraud.validationflow.application.execution.ports.IdempotencyRepository idempotencyRepository;

	@Autowired
	MockMvc mvc;

	@Test
	void healthEndpointsRespond200() throws Exception {
		for (String path : new String[] { "/actuator/health", "/actuator/health/liveness",
				"/actuator/health/readiness", "/api/v1/health" }) {
			mvc.perform(get(path)).andExpect(status().isOk());
		}
	}

}
