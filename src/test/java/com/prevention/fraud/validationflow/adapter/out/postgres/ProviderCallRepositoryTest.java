package com.prevention.fraud.validationflow.adapter.out.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.prevention.fraud.validationflow.application.provider.ProviderCall;
import com.prevention.fraud.validationflow.application.provider.ProviderCallRepository;
import com.prevention.fraud.validationflow.application.provider.ProviderResult;

@Testcontainers(disabledWithoutDocker = true)
class ProviderCallRepositoryTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16");

	/** Inserts the flow_execution row the provider_call FK points to. */
	static UUID execution(String tenant) throws Exception {
		var flowId = UUID.randomUUID();
		var executionId = UUID.randomUUID();
		try (var c = java.sql.DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
				var s = c.createStatement()) {
			s.executeUpdate("INSERT INTO flow_definition (id, tenant_id, flow_key, version, status, user_type, context, "
					+ "display_name, graph_definition, created_by) VALUES ('" + flowId + "', '" + tenant + "', 'f-" + flowId
					+ "', 1, 'DRAFT', 'u', 'c', 'f', '{}', 'test')");
			s.executeUpdate("INSERT INTO flow_execution (id, tenant_id, flow_definition_id, flow_key, flow_version, "
					+ "flow_snapshot, status, input_data) VALUES ('" + executionId + "', '" + tenant + "', '" + flowId
					+ "', 'f', 1, '{}', 'WAITING', '{}')");
		}
		return executionId;
	}

	static ProviderCall call(String tenant, UUID executionId, ProviderCall.Status status, String tokenHash) {
		var now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		return new ProviderCall(UUID.randomUUID(), tenant, executionId, "n1", "fake", "ref-1",
				ProviderResult.Mode.POLL, status, 0, now.plusSeconds(30), now.plusSeconds(3600), tokenHash, now, now);
	}

	@Test
	void saveReadUpdateAndTenantIsolation() throws Exception {
		Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).load().migrate();
		var repo = JpaTestContext.start(pg).getBean(ProviderCallRepository.class);

		var token = ProviderCall.newToken();
		var a = call("tenant-a", execution("tenant-a"), ProviderCall.Status.PENDING, ProviderCall.hashToken(token));
		repo.save(a);
		var read = repo.find("tenant-a", a.id()).orElseThrow();
		assertEquals(a, read);
		assertNotEquals(token, read.callbackTokenHash());

		var done = new ProviderCall(a.id(), a.tenantId(), a.executionId(), a.nodeId(), a.providerKey(), a.externalRef(),
				a.mode(), ProviderCall.Status.COMPLETED, 2, null, a.deadline(), a.callbackTokenHash(), a.createdAt(),
				Instant.now().truncatedTo(ChronoUnit.MILLIS));
		repo.save(done);
		assertEquals(done, repo.find("tenant-a", a.id()).orElseThrow());

		assertTrue(repo.find("tenant-b", a.id()).isEmpty());

		// FK: a call for an execution that does not exist is rejected
		var orphan = call("tenant-a", UUID.randomUUID(), ProviderCall.Status.PENDING, null);
		org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
				() -> repo.save(orphan));
	}

}
