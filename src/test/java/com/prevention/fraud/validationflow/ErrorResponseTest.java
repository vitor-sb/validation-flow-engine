package com.prevention.fraud.validationflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.prevention.fraud.validationflow.application.execution.ports.ExecutionRepository;
import com.prevention.fraud.validationflow.application.flow.ports.FlowRepository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
		"spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
				+ "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
				+ "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
		"app.security.api-keys[0].key=k", "app.security.api-keys[0].tenant-id=t1",
		"app.security.api-keys[0].scopes=flow:write,flow:activate,flow:read,validation:read,validation:execute"
})
@AutoConfigureMockMvc
class ErrorResponseTest {

	@Autowired
	MockMvc mvc;

	@MockitoBean
	FlowRepository flows;

	@MockitoBean
	ExecutionRepository executions;

	private static void assertErrorResponse(ResultActions r, int status, String code) throws Exception {
		r.andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code))
				.andExpect(jsonPath("$.retryable").isBoolean()).andExpect(jsonPath("$.details").isArray())
				.andExpect(jsonPath("$.timestamp").doesNotExist()).andExpect(jsonPath("$.trace").doesNotExist())
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Exception"))));
	}

	@Test
	void invalidEnumQueryParam() throws Exception {
		assertErrorResponse(mvc.perform(get("/api/v1/flows?status=NOPE").header("X-API-Key", "k")), 400, "BAD_REQUEST");
	}

	@Test
	void malformedJson() throws Exception {
		assertErrorResponse(mvc.perform(post("/api/v1/flows").header("X-API-Key", "k")
				.contentType(MediaType.APPLICATION_JSON).content("{oops")), 400, "MALFORMED_REQUEST");
	}

	@Test
	void invalidUuidInPath() throws Exception {
		assertErrorResponse(mvc.perform(get("/api/v1/flows/not-a-uuid").header("X-API-Key", "k")), 400, "BAD_REQUEST");
	}

	@Test
	void unsupportedMethod() throws Exception {
		assertErrorResponse(mvc.perform(delete("/api/v1/flows").header("X-API-Key", "k")), 405, "METHOD_NOT_ALLOWED");
	}

	@Test
	void unknownRoute() throws Exception {
		assertErrorResponse(mvc.perform(get("/api/v1/nope").header("X-API-Key", "k")), 404, "NOT_FOUND");
	}

	@Test
	void unexpectedErrorDoesNotLeakDetails() throws Exception {
		when(flows.list(any(), any(), any(), any(), any(), anyInt(), anyInt()))
				.thenThrow(new IllegalStateException("secret internal detail"));
		assertErrorResponse(mvc.perform(get("/api/v1/flows").header("X-API-Key", "k")), 500, "INTERNAL_ERROR");
		mvc.perform(get("/api/v1/flows").header("X-API-Key", "k"))
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
	}

	private static int anyInt() {
		return org.mockito.ArgumentMatchers.anyInt();
	}

}
