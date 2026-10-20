package com.prevention.fraud.validationflow;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// Skipped (not failed) when Docker is absent; CI with Docker runs it.
@Testcontainers(disabledWithoutDocker = true)
class MigrationsTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16");

	private static final String FLOW = "INSERT INTO flow_definition (id, tenant_id, flow_key, version, status, user_type, "
			+ "context, display_name, graph_definition, created_by) VALUES (gen_random_uuid(), 't1', 'k', %d, '%s', 'u', 'c', 'n', '{}', 'me')";

	@Test
	void migrationsApplyAndEnforceConstraints() throws SQLException {
		Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).load().migrate();
		try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
				Statement s = c.createStatement()) {
			ResultSet rs = s.executeQuery("SELECT count(*) FROM information_schema.columns WHERE column_name = 'tenant_id' "
					+ "AND table_name IN ('flow_definition','flow_execution','node_execution','execution_audit_log','idempotency_key')");
			rs.next();
			assertEquals(5, rs.getInt(1));
			rs = s.executeQuery("SELECT count(*) FROM information_schema.columns WHERE table_name = 'flow_definition' "
					+ "AND column_name = 'graph_definition' AND data_type = 'jsonb'");
			rs.next();
			assertEquals(1, rs.getInt(1));

			s.execute(String.format(FLOW, 1, "ACTIVE"));
			assertThrows(SQLException.class, () -> s.execute(String.format(FLOW, 1, "DRAFT"))); // unique version
			assertThrows(SQLException.class, () -> s.execute(String.format(FLOW, 2, "ACTIVE"))); // 2nd ACTIVE
			s.execute(String.format(FLOW, 3, "DRAFT"));
		}
	}

}
