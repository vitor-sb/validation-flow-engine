package com.prevention.fraud.validationflow.adapter.in.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.prevention.fraud.validationflow.application.execution.ExecutionService;
import com.prevention.fraud.validationflow.application.execution.idempotency.IdempotentExecutionService;
import com.prevention.fraud.validationflow.config.security.TenantPrincipal;
import com.prevention.fraud.validationflow.domain.execution.FlowExecution;
import com.prevention.fraud.validationflow.domain.flow.FlowDefinition;
import com.prevention.fraud.validationflow.domain.flow.FlowStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** POST /executions answers 202 for WAITING and 201 otherwise (standalone: no auth, no database). */
class ExecutionAcceptedTest {

	private static final String BODY = "{\"userType\":\"PF\",\"context\":\"C\",\"inputData\":{}}";

	private static FlowExecution running() {
		var flow = new FlowDefinition(UUID.randomUUID(), "t", "k", 1, FlowStatus.ACTIVE, "PF", "C", "d", null, Map.of(),
				List.of(), null, "u", Instant.now());
		return FlowExecution.pending("t", flow, null, Map.of()).start();
	}

	private static MockMvc mvc(FlowExecution result) {
		var service = mock(ExecutionService.class);
		when(service.execute(any(), any(), any(), any(), any(), any())).thenReturn(result);
		return MockMvcBuilders.standaloneSetup(new ExecutionController(service, mock(IdempotentExecutionService.class)))
				.setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
					public boolean supportsParameter(MethodParameter p) {
						return p.getParameterType() == TenantPrincipal.class;
					}

					public Object resolveArgument(MethodParameter p, ModelAndViewContainer c, NativeWebRequest r,
							WebDataBinderFactory b) {
						return new TenantPrincipal("t", "u");
					}
				}).build();
	}

	@Test
	void waitingIs202AndOthersAre201() throws Exception {
		var waiting = running().waitAt("n", Instant.now(), "r", Map.of());
		mvc(waiting).perform(post("/api/v1/executions").contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("WAITING"));
		mvc(running()).perform(post("/api/v1/executions").contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isCreated());
	}

}
