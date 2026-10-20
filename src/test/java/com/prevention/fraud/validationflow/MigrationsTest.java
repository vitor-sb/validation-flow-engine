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

	private static final String ENTRY = "INSERT INTO provider_cache_entry (id, tenant_id, provider_key, operation, request_key, response, "
			+ "data_category, fetched_at, valid_until) VALUES (gen_random_uuid(), '%s', '%s', '%s', '%s', '{\"v\":\"%s\"}', 'CADASTRAL', "
			+ "now() - interval '%d hours', now() + interval '1 day')";

	private static final String POLICY = "INSERT INTO provider_cache_policy (tenant_id, provider_key, operation, data_category, ttl_seconds) "
			+ "VALUES ('%s', '%s', '%s', 'CADASTRAL', %d)";

	private static String rows(Statement s, String sql) throws SQLException {
		StringBuilder out = new StringBuilder();
		ResultSet rs = s.executeQuery(sql);
		while (rs.next()) {
			out.append(out.isEmpty() ? "" : ",").append(rs.getString(1));
		}
		return out.toString();
	}

	@Test
	void defaultCacheDataMovesToTheSingleOperation() throws SQLException {
		Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).cleanDisabled(false).load().clean();
		Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).target("12").load().migrate();
		try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
				Statement s = c.createStatement()) {
			s.execute(String.format(ENTRY, "t1", "brasilapi-cnpj", "default", "a", "default-a", 5)); // moves
			s.execute(String.format(ENTRY, "t1", "brasilapi-cnpj", "default", "b", "default-b-new", 1)); // conflict: default newer
			s.execute(String.format(ENTRY, "t1", "brasilapi-cnpj", "cnpj", "b", "cnpj-b-old", 5));
			s.execute(String.format(ENTRY, "t1", "brasilapi-cnpj", "default", "c", "default-c-old", 5)); // conflict: cnpj newer
			s.execute(String.format(ENTRY, "t1", "brasilapi-cnpj", "cnpj", "c", "cnpj-c-new", 1));
			s.execute(String.format(ENTRY, "t2", "brasilapi-cnpj", "default", "a", "t2-a", 1)); // other tenant
			s.execute(String.format(ENTRY, "t1", "other", "default", "a", "other-a", 1)); // untouched
			s.execute(String.format(POLICY, "t1", "brasilapi-cnpj", "default", 10)); // moves
			s.execute(String.format(POLICY, "t2", "brasilapi-cnpj", "default", 20)); // conflict: cnpj wins
			s.execute(String.format(POLICY, "t2", "brasilapi-cnpj", "cnpj", 30));
			s.execute(String.format(POLICY, "t1", "other", "default", 40)); // untouched

			Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).load().migrate();

			assertEquals("brasilapi-cnpj|t1|a|cnpj|default-a,brasilapi-cnpj|t1|b|cnpj|default-b-new,"
					+ "brasilapi-cnpj|t1|c|cnpj|cnpj-c-new,brasilapi-cnpj|t2|a|cnpj|t2-a,other|t1|a|default|other-a",
					rows(s, "SELECT provider_key||'|'||tenant_id||'|'||request_key||'|'||operation||'|'||(response->>'v') "
							+ "FROM provider_cache_entry ORDER BY provider_key, tenant_id, request_key"));
			assertEquals("brasilapi-cnpj|t1|cnpj|10,brasilapi-cnpj|t2|cnpj|30,other|t1|default|40",
					rows(s, "SELECT provider_key||'|'||tenant_id||'|'||operation||'|'||ttl_seconds "
							+ "FROM provider_cache_policy ORDER BY provider_key, tenant_id"));
		}
	}

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
