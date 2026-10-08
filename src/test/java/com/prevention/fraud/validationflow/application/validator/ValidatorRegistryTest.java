package com.prevention.fraud.validationflow.application.validator;

import com.prevention.fraud.validationflow.application.execution.ports.ExecutionRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.prevention.fraud.validationflow.application.flow.ports.FlowRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
		"spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
				+ "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
				+ "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
		"app.security.api-keys[0].key=k", "app.security.api-keys[0].tenant-id=t1",
		"app.security.api-keys[0].scopes=flow:write,flow:read"
})
@AutoConfigureMockMvc
@Import(ValidatorRegistryTest.FakeConfig.class)
class ValidatorRegistryTest {

	static ValidatorStrategy fake(String key) {
		return new ValidatorStrategy() {
			public String key() {
				return key;
			}

			public ValidationResult execute(ValidationInput input) {
				return new ValidationResult(true, Map.of("echo", input.data()));
			}

			public Set<String> capabilities() {
				return Set.of("FAKE");
			}
		};
	}

	@TestConfiguration
	static class FakeConfig {
		@Bean
		ValidatorStrategy fakeValidator() {
			return fake("fake");
		}
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	ValidatorRegistry registry;

	@MockitoBean
	FlowRepository repository;

	@MockitoBean
	com.prevention.fraud.validationflow.application.execution.ports.ExecutionRepository executionRepository;

	@MockitoBean
	com.prevention.fraud.validationflow.application.execution.ports.IdempotencyRepository idempotencyRepository;

	@Test
	void discoversNewValidatorAndExecutesItViaRegistry() {
		var result = registry.find("fake").orElseThrow()
				.execute(new ValidatorStrategy.ValidationInput(Map.of("a", 1), Map.of()));
		assertThat(result.success()).isTrue();
		assertThat(result.output()).containsEntry("echo", Map.of("a", 1));
	}

	@Test
	void rejectsDuplicateKeys() {
		assertThatThrownBy(() -> new ValidatorRegistry(List.of(fake("x"), fake("x"))))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("x");
	}

	@Test
	void nodeTypesEndpoints() throws Exception {
		mvc.perform(get("/api/v1/node-types").header("X-API-Key", "k")).andExpect(status().isOk())
				.andExpect(jsonPath("$[0].key").value("fake"));
		mvc.perform(get("/api/v1/node-types/fake").header("X-API-Key", "k")).andExpect(status().isOk())
				.andExpect(jsonPath("$.capabilities[0]").value("FAKE"));
		mvc.perform(get("/api/v1/node-types/nope").header("X-API-Key", "k")).andExpect(status().isNotFound());
	}

	@Test
	void graphValidationUsesRegistry() throws Exception {
		String graph = """
				{"graphDefinition":{"startNodeId":"s","nodes":{
				 "s":{"type":"START","transitions":[{"to":"v"}]},
				 "v":{"type":"VALIDATION","config":{"validatorType":"%s"},"transitions":[{"to":"e"}]},
				 "e":{"type":"END"}}}}""";
		mvc.perform(post("/api/v1/flows/validate").header("X-API-Key", "k").contentType(MediaType.APPLICATION_JSON)
				.content(graph.formatted("fake"))).andExpect(jsonPath("$.valid").value(true));
		mvc.perform(post("/api/v1/flows/validate").header("X-API-Key", "k").contentType(MediaType.APPLICATION_JSON)
				.content(graph.formatted("other"))).andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_VALIDATOR_TYPE"));
	}

}
