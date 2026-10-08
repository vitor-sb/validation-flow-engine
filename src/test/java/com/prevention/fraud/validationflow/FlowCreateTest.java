package com.prevention.fraud.validationflow;

import com.prevention.fraud.validationflow.application.execution.ports.ExecutionRepository;
import com.prevention.fraud.validationflow.domain.flow.FlowDefinition;
import com.prevention.fraud.validationflow.domain.flow.FlowStatus;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.prevention.fraud.validationflow.application.flow.ports.FlowRepository;

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
		"app.security.api-keys[0].scopes=flow:write,flow:activate,flow:read"
})
@AutoConfigureMockMvc
class FlowCreateTest {

	@Autowired
	MockMvc mvc;

	@MockitoBean
	FlowRepository repository;

	@MockitoBean
	com.prevention.fraud.validationflow.application.execution.ports.ExecutionRepository executionRepository;

	@MockitoBean
	com.prevention.fraud.validationflow.application.execution.ports.IdempotencyRepository idempotencyRepository;

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

	@Test
	void uniqueViolationReturns409WithErrorResponse() throws Exception {
		when(repository.save(any())).thenThrow(new DuplicateKeyException("flow_definition_tenant_id_flow_key_version_key"));
		mvc.perform(post("/api/v1/flows").header("X-API-Key", "k").contentType(MediaType.APPLICATION_JSON)
				.content(VALID)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
	}

	@Test
	void jpaUniqueViolationReturns409ButOtherIntegrityErrorsReturn500() throws Exception {
		var unique = new org.springframework.dao.DataIntegrityViolationException("x",
				new java.sql.SQLException("duplicate key", "23505"));
		when(repository.save(any())).thenThrow(unique);
		mvc.perform(post("/api/v1/flows").header("X-API-Key", "k").contentType(MediaType.APPLICATION_JSON)
				.content(VALID)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
		var notNull = new org.springframework.dao.DataIntegrityViolationException("x",
				new java.sql.SQLException("null value", "23502"));
		org.mockito.Mockito.doThrow(notNull).when(repository).save(any());
		mvc.perform(post("/api/v1/flows").header("X-API-Key", "k").contentType(MediaType.APPLICATION_JSON)
				.content(VALID)).andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
	}

	@Test
	void validateReturnsErrorsWithoutPersisting() throws Exception {
		mvc.perform(post("/api/v1/flows/validate").header("X-API-Key", "k").contentType(MediaType.APPLICATION_JSON)
				.content("{\"graphDefinition\":{\"nodes\":{}}}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false))
				.andExpect(jsonPath("$.errors[0].code").exists());
		org.mockito.Mockito.verifyNoInteractions(repository);
	}

	@Test
	void activateInvalidGraphReturns422() throws Exception {
		var id = java.util.UUID.randomUUID();
		when(repository.findById("t1", id)).thenReturn(java.util.Optional.of(new com.prevention.fraud.validationflow.domain.flow.FlowDefinition(
				id, "t1", "kyc", 1, com.prevention.fraud.validationflow.domain.flow.FlowStatus.DRAFT, "PF", "C", "d", null,
				java.util.Map.of("nodes", java.util.Map.of()), java.util.List.of(), null, "t1", java.time.Instant.now())));
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/flows/" + id + "/activate")
				.header("X-API-Key", "k")).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("INVALID_FLOW"));
		org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).activate(any(), any());
	}

	@Test
	void unknownFlowReturns404() throws Exception {
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.patch("/api/v1/flows/" + java.util.UUID.randomUUID() + "/archive").header("X-API-Key", "k"))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FLOW_NOT_FOUND"));
	}

}
