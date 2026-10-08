package com.prevention.fraud.validationflow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.prevention.fraud.validationflow.application.execution.ports.ExecutionRepository;
import com.prevention.fraud.validationflow.application.flow.ports.FlowRepository;

@SpringBootTest(properties = {
		"spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
				+ "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
				+ "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
		"app.security.api-keys[0].key=k", "app.security.api-keys[0].tenant-id=t1",
		"app.security.api-keys[0].scopes=flow:write,validation:execute",
		"app.limits.max-body-bytes=1000", "app.limits.max-nodes=3", "app.limits.max-transitions=3"
})
@AutoConfigureMockMvc
class InputLimitsTest {

	@Autowired
	MockMvc mvc;

	@MockitoBean
	FlowRepository flows;

	@MockitoBean
	ExecutionRepository executions;

	@MockitoBean
	com.prevention.fraud.validationflow.application.execution.ports.IdempotencyRepository idempotencyRepository;

	static String graph(int nodes) {
		StringBuilder sb = new StringBuilder("{\"startNodeId\":\"n0\",\"nodes\":{");
		for (int i = 0; i < nodes; i++) {
			sb.append(i > 0 ? "," : "").append("\"n").append(i).append("\":{\"type\":\"").append(i == 0 ? "START" : i == nodes - 1 ? "END" : "DECISION")
					.append("\",\"transitions\":[").append(i < nodes - 1 ? "{\"to\":\"n" + (i + 1) + "\"}" : "").append("]}");
		}
		return sb.append("}}").toString();
	}


	@Test
	void bodyAboveLimitIs413ForContentLengthAndChunked() throws Exception {
		String big = "{\"graphDefinition\":{},\"pad\":\"" + "x".repeat(1000) + "\"}";
		mvc.perform(post("/api/v1/flows/validate").header("X-API-Key", "k").contentType(MediaType.APPLICATION_JSON).content(big))
				.andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"))
				.andExpect(jsonPath("$.retryable").value(false));
		mvc.perform(post("/api/v1/flows/validate").header("X-API-Key", "k").contentType(MediaType.APPLICATION_JSON)
				.header("Transfer-Encoding", "chunked").with(r -> {
					r.setContent(big.getBytes());
					r.removeHeader("Content-Length");
					return r;
				})).andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
	}

	@Test
	void bodyBelowLimitPasses() throws Exception {
		mvc.perform(post("/api/v1/flows/validate").header("X-API-Key", "k").contentType(MediaType.APPLICATION_JSON)
				.content("{\"graphDefinition\":" + graph(3) + "}")).andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));
	}

	@Test
	void graphAboveLimitIsRejected() throws Exception {
		mvc.perform(post("/api/v1/flows/validate").header("X-API-Key", "k").contentType(MediaType.APPLICATION_JSON)
				.content("{\"graphDefinition\":" + graph(4) + "}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.valid").value(false)).andExpect(jsonPath("$.errors[0].code").value("GRAPH_TOO_LARGE"));
		mvc.perform(post("/api/v1/flows").header("X-API-Key", "k").contentType(MediaType.APPLICATION_JSON)
				.content("{\"flowKey\":\"f\",\"userType\":\"PF\",\"context\":\"c\",\"displayName\":\"d\",\"graphDefinition\":" + graph(4) + "}"))
				.andExpect(status().isUnprocessableEntity());
	}

	@Test
	void correlationIdAbove128CharsIs400() throws Exception {
		mvc.perform(post("/api/v1/executions").header("X-API-Key", "k").contentType(MediaType.APPLICATION_JSON)
				.content("{\"userType\":\"PF\",\"context\":\"c\",\"inputData\":{},\"correlationId\":\"" + "c".repeat(129) + "\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
	}

}
