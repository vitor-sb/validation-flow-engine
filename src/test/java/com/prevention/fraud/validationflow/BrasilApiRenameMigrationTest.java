package com.prevention.fraud.validationflow;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StreamUtils;

import com.prevention.fraud.validationflow.application.execution.ExecutionService;
import com.prevention.fraud.validationflow.application.flow.FlowService;
import com.prevention.fraud.validationflow.application.provider.ProviderConfig;
import com.prevention.fraud.validationflow.application.provider.ProviderConfigService;
import com.prevention.fraud.validationflow.domain.execution.ExecutionStatus;
import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V14 on an execution parked before the rename: the later node used `brasilapi-cnpj`, and resumes after the migration. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BrasilApiRenameMigrationTest {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	FlowService flows;

	@Autowired
	ExecutionService executions;

	@Autowired
	ProviderConfigService configs;

	private void migrate() throws Exception {
		jdbc.execute(StreamUtils.copyToString(new ClassPathResource("db/migration/V14__rename_brasilapi_cnpj_to_brasilapi.sql")
				.getInputStream(), StandardCharsets.UTF_8));
	}

	private String snapshot(Object id) {
		return jdbc.queryForObject("SELECT flow_snapshot::text FROM flow_execution WHERE id = ?", String.class, id);
	}

	/** Back to the shape stored before the rename (old validator, no operation). */
	private void makeOld(Object id) {
		jdbc.update("UPDATE flow_execution SET flow_snapshot = (replace(flow_snapshot::text, '\"validatorType\": \"brasilapi\"', "
				+ "'\"validatorType\": \"brasilapi-cnpj\"')::jsonb) #- '{nodes,v,config,operation}' WHERE id = ?", id);
	}

	@Test
	void waitingExecutionWithOldValidatorResumesAfterMigration() throws Exception {
		HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		api.createContext("/", ex -> {
			byte[] b = "{\"razao_social\":\"ACME\",\"situacao_cadastral\":2,\"descricao_situacao_cadastral\":\"ATIVA\"}"
					.getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().add("Content-Type", "application/json");
			ex.sendResponseHeaders(200, b.length);
			ex.getResponseBody().write(b);
			ex.close();
		});
		api.start();
		try {
			configs.save(new ProviderConfig("ren-t", "brasilapi", true, "http://127.0.0.1:" + api.getAddress().getPort(), null, null,
					null, "u", Instant.EPOCH));
			Map<String, Object> g = Map.of("startNodeId", "s", "nodes", Map.of(
					"s", Map.of("type", "START", "transitions", List.of(Map.of("to", "h"))),
					"h", Map.of("type", "HUMAN_APPROVAL", "config", Map.of("reason", "review", "deadline", "PT1H"),
							"transitions", List.of(Map.of("to", "v", "label", "APPROVED"), Map.of("to", "e", "label", "REJECTED"))),
					"v", Map.of("type", "VALIDATION", "config", Map.of("validatorType", "brasilapi", "operation", "cnpj",
							"params", Map.of("query", Map.of("cnpj", "companyCnpj"))), "transitions", List.of(Map.of("to", "e"))),
					"e", Map.of("type", "END")));
			var d = flows.createDraft("ren-t", "t", new FlowService.CreateFlow("k-ren", "REN", "CREN", "d", null, g, List.of(), null));
			flows.activate("ren-t", d.id());
			var waiting = executions.execute("ren-t", null, "REN", "CREN", Map.of("companyCnpj", "11222333000181"), null);
			assertEquals(ExecutionStatus.WAITING, waiting.status());
			String migrated = snapshot(waiting.id());

			makeOld(waiting.id());
			assertTrue(snapshot(waiting.id()).contains("brasilapi-cnpj"));
			migrate();
			assertEquals(migrated, snapshot(waiting.id()), "same snapshot as one created after the rename");
			migrate();
			assertEquals(migrated, snapshot(waiting.id()), "idempotent");

			var done = executions.resume("ren-t", waiting.id(), "APPROVED", null, null, "alice");
			assertEquals(ExecutionStatus.COMPLETED, done.status());

			// terminal executions are left alone
			makeOld(waiting.id());
			String old = snapshot(waiting.id());
			migrate();
			assertEquals(old, snapshot(waiting.id()));
		}
		finally {
			api.stop(0);
		}
	}

}
