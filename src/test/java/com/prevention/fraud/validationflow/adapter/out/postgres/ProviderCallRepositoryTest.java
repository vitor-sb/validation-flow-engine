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

	static ProviderCall call(String tenant, ProviderCall.Status status, String tokenHash) {
		var now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		return new ProviderCall(UUID.randomUUID(), tenant, UUID.randomUUID(), "n1", "fake", "ref-1",
				ProviderResult.Mode.POLL, status, 0, now.plusSeconds(30), now.plusSeconds(3600), tokenHash, now, now);
	}

	@Test
	void saveReadUpdateAndTenantIsolation() {
		Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).load().migrate();
		var repo = JpaTestContext.start(pg).getBean(ProviderCallRepository.class);

		var token = ProviderCall.newToken();
		var a = call("tenant-a", ProviderCall.Status.PENDING, ProviderCall.hashToken(token));
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
	}

}
