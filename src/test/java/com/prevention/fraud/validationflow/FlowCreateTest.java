package com.prevention.fraud.validationflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.prevention.fraud.validationflow.application.FlowRepository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
		"spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
				+ "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
				+ "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
		"app.security.api-keys[0].key=k", "app.security.api-keys[0].tenant-id=t1",
		"app.security.api-keys[0].scopes=flow:write"
})
@AutoConfigureMockMvc
class FlowCreateTest {

	@Autowired
	MockMvc mvc;

	@MockitoBean
	FlowRepository repository;

	static final String VALID = """
			{"flowKey":"kyc","userType":"PF","context":"ONBOARDING","displayName":"KYC",
			 "graphDefinition":{"startNodeId":"s","nodes":[],"edges":[]},
			 "inputContract":[{"name":"cpf","type":"STRING","required":true}]}""";

	@Test
	void createsDraftWithTenantFromCredential() throws Exception {
		when(repository.nextVersion("t1", "kyc")).thenReturn(3);
		when(repository.save(any())).then(returnsFirstArg());
		mvc.perform(post("/api/v1/flows").header("X-API-Key", "k").contentType(MediaType.APPLICATION_JSON)
				.content(VALID.replace("\"flowKey\"", "\"tenantId\":\"evil\",\"flowKey\"")))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT"))
				.andExpect(jsonPath("$.version").value(3)).andExpect(jsonPath("$.createdBy").value("t1"))
				.andExpect(jsonPath("$.createdAt").exists());
		verify(repository).nextVersion(eq("t1"), eq("kyc"));
	}

	@Test
	void blankFieldsReturn400WithErrorResponse() throws Exception {
		mvc.perform(post("/api/v1/flows").header("X-API-Key", "k").contentType(MediaType.APPLICATION_JSON)
				.content("{\"flowKey\":\" \",\"userType\":\"PF\",\"context\":\"C\",\"displayName\":\"d\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.details").isArray());
	}

}
