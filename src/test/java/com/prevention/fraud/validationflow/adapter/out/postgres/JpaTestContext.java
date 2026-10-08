package com.prevention.fraud.validationflow.adapter.out.postgres;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.prevention.fraud.validationflow.application.execution.ports.ExecutionRepository;
import com.prevention.fraud.validationflow.application.execution.ports.IdempotencyRepository;
import com.prevention.fraud.validationflow.application.flow.ports.FlowRepository;

import tools.jackson.databind.json.JsonMapper;

/** Minimal non-web Spring context (JPA + Flyway + the postgres adapter) for repository tests that build their own objects. */
final class JpaTestContext {

	@org.springframework.boot.test.context.TestConfiguration
	@EnableAutoConfiguration
	@Import({ JpaFlowRepository.class, JpaExecutionRepository.class, JdbcIdempotencyRepository.class })
	static class Config {

		@org.springframework.context.annotation.Bean
		JsonMapper jsonMapper() {
			return JsonMapper.builder().build();
		}

	}

	private JpaTestContext() {
	}

	static ConfigurableApplicationContext start(PostgreSQLContainer pg) {
		return new SpringApplicationBuilder(Config.class).web(WebApplicationType.NONE)
				.properties("spring.datasource.url=" + pg.getJdbcUrl(), "spring.datasource.username=" + pg.getUsername(),
						"spring.datasource.password=" + pg.getPassword(), "spring.jpa.hibernate.ddl-auto=validate",
						"spring.jpa.open-in-view=false")
				.run();
	}

	static FlowRepository flowRepository(ConfigurableApplicationContext ctx) {
		return ctx.getBean(FlowRepository.class);
	}

	static ExecutionRepository executionRepository(ConfigurableApplicationContext ctx) {
		return ctx.getBean(ExecutionRepository.class);
	}

	static IdempotencyRepository idempotencyRepository(ConfigurableApplicationContext ctx) {
		return ctx.getBean(IdempotencyRepository.class);
	}

}
