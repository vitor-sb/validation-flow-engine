package com.prevention.fraud.validationflow.adapter.out.postgres;

import com.prevention.fraud.validationflow.domain.flow.FlowDefinition;
import com.prevention.fraud.validationflow.domain.flow.GraphValidator;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.prevention.fraud.validationflow.application.flow.FlowService;
import com.prevention.fraud.validationflow.domain.flow.FlowStatus;
import com.prevention.fraud.validationflow.domain.flow.InputField;

import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers(disabledWithoutDocker = true)
class JdbcFlowRepositoryTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16");

	@Test
	void tenantsCanCreateSameFlowKeyWithoutCollision() {
		Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).load().migrate();
		var repo = new JdbcFlowRepository(
				new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())),
				JsonMapper.builder().build());
		var service = new FlowService(repo, new com.prevention.fraud.validationflow.domain.flow.GraphValidator(t -> false));
		var cmd = new FlowService.CreateFlow("kyc", "PF", "ONB", "KYC", null, Map.of("nodes", List.of()),
				List.of(new InputField("cpf", "STRING", true)), null);
		assertEquals(1, service.createDraft("a", "a", cmd).version());
		assertEquals(2, service.createDraft("a", "a", cmd).version());
		var b = service.createDraft("b", "b", cmd);
		assertEquals(1, b.version());
		assertEquals(FlowStatus.DRAFT, b.status());
		// tenant isolation: b's version counter ignores a's rows, and b's row is untouched by a's creates
		assertEquals(3, repo.nextVersion("a", "kyc"));
		assertEquals(2, repo.nextVersion("b", "kyc"));
		// unique (tenant_id, flow_key, version) violation surfaces as DuplicateKeyException (mapped to 409)
		assertThrows(DuplicateKeyException.class, () -> repo.save(new com.prevention.fraud.validationflow.domain.flow.FlowDefinition(java.util.UUID.randomUUID(),
				b.tenantId(), b.flowKey(), b.version(), b.status(), b.userType(), b.context(), b.displayName(),
				b.description(), b.graphDefinition(), b.inputContract(), b.metadata(), b.createdBy(), b.createdAt())));
	}

}
