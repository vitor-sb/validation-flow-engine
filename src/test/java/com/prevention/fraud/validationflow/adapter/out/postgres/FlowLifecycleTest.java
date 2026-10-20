package com.prevention.fraud.validationflow.adapter.out.postgres;

import com.prevention.fraud.validationflow.domain.flow.FlowDefinition;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.prevention.fraud.validationflow.application.flow.FlowException;
import com.prevention.fraud.validationflow.application.flow.FlowService;
import com.prevention.fraud.validationflow.application.flow.ports.FlowRepository;
import com.prevention.fraud.validationflow.domain.flow.FlowStatus;
import com.prevention.fraud.validationflow.domain.flow.GraphValidator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers(disabledWithoutDocker = true)
class FlowLifecycleTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16");

	static FlowRepository repo;

	static FlowService service;

	static final Map<String, Object> GRAPH = Map.of("startNodeId", "s", "nodes", Map.of(
			"s", Map.of("type", "START", "transitions", List.of(Map.of("to", "e"))),
			"e", Map.of("type", "END")));

	@BeforeAll
	static void setUp() {
		Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).load().migrate();
		repo = JpaTestContext.flowRepository(JpaTestContext.start(pg));
		service = new FlowService(repo, new GraphValidator(t -> false));
	}

	static FlowService.CreateFlow cmd(String key, String ctx) {
		return new FlowService.CreateFlow(key, "PF", ctx, "d", null, GRAPH, List.of(), null);
	}

	@Test
	void activationArchivesPreviousPutOnActiveCreatesDraftAndArchivedStaysReadable() {
		var v1 = service.createDraft("a", "a", cmd("k1", "C1"));
		assertEquals(FlowStatus.ACTIVE, service.activate("a", v1.id()).status());
		var v2 = service.update("a", "a", v1.id(), cmd(null, "C1"));
		assertEquals(FlowStatus.DRAFT, v2.status());
		assertEquals(2, v2.version());
		assertEquals(FlowStatus.ACTIVE, service.get("a", v1.id()).status()); // active untouched
		service.activate("a", v2.id());
		assertEquals(FlowStatus.ARCHIVED, service.get("a", v1.id()).status());
		assertEquals(FlowStatus.ACTIVE, service.get("a", v2.id()).status());
		// archived graph is immutable
		assertEquals(FlowException.Kind.CONFLICT,
				assertThrows(FlowException.class, () -> service.update("a", "a", v1.id(), cmd(null, "C1"))).kind());
		// draft is edited in place
		var d = service.createDraft("a", "a", cmd("k2", "C2"));
		assertEquals("C3", service.update("a", "a", d.id(), cmd(null, "C3")).context());
	}

	@Test
	void tenantBCannotReadOrModifyTenantAFlow() {
		var f = service.createDraft("ta", "ta", cmd("iso", "CI"));
		for (Runnable op : List.<Runnable>of(() -> service.get("tb", f.id()),
				() -> service.update("tb", "tb", f.id(), cmd(null, "CI")), () -> service.activate("tb", f.id()),
				() -> service.archive("tb", f.id()))) {
			assertEquals(FlowException.Kind.NOT_FOUND, assertThrows(FlowException.class, op::run).kind());
		}
		assertFalse(repo.activate("tb", f.id()));
		assertFalse(repo.archive("tb", f.id()));
		assertEquals(FlowStatus.DRAFT, service.get("ta", f.id()).status());
	}

	@Test
	void concurrentActivationOfSameSelectorOnlyOneSucceeds() throws Exception {
		var a = service.createDraft("c", "c", cmd("ca", "CC"));
		var b = service.createDraft("c", "c", cmd("cb", "CC"));
		var start = new CountDownLatch(1);
		var pool = Executors.newFixedThreadPool(2);
		List<Callable<Boolean>> tasks = List.of(a, b).stream().<Callable<Boolean>>map(f -> () -> {
			start.await();
			try {
				return repo.activate("c", f.id());
			}
			catch (DataIntegrityViolationException e) {
				return false;
			}
		}).toList();
		var futures = tasks.stream().map(pool::submit).toList();
		start.countDown();
		int wins = 0;
		for (var f : futures) {
			wins += f.get() ? 1 : 0;
		}
		pool.shutdown();
		assertEquals(1, wins);
		assertEquals(1, new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()))
				.queryForObject("SELECT count(*) FROM flow_definition WHERE tenant_id='c' AND status='ACTIVE'", Integer.class));
	}

	@Test
	void listFiltersPaginatesAndIsolatesTenants() {
		service.createDraft("l1", "l1", cmd("lk", "LC"));
		var v2 = service.createDraft("l1", "l1", cmd("lk", "LC"));
		service.createDraft("l1", "l1", cmd("other", "LD"));
		service.createDraft("l2", "l2", cmd("lk", "LC"));
		service.activate("l1", v2.id());
		assertEquals(3, service.list("l1", null, null, null, null, 0, 20).total());
		assertEquals(2, service.list("l1", "lk", null, null, null, 0, 20).total());
		assertEquals(1, service.list("l1", null, FlowStatus.ACTIVE, "PF", "LC", 0, 20).total());
		var page = service.list("l1", null, null, null, null, 1, 2);
		assertEquals(1, page.items().size());
		assertEquals(3, page.total());
		assertEquals(1, service.list("l2", null, null, null, null, 0, 20).total()); // no l1 rows leak
	}

	@Test
	void resolveActiveByFlowKeyOrSelectorIsTenantScopedAndNotFoundOtherwise() {
		var f = service.createDraft("r1", "r1", cmd("rk", "RC"));
		assertEquals(FlowException.Kind.NOT_FOUND, assertThrows(FlowException.class,
				() -> service.resolveActive("r1", "rk", null, null)).kind()); // DRAFT is not resolvable
		service.activate("r1", f.id());
		assertEquals(f.id(), service.resolveActive("r1", "rk", null, null).id());
		assertEquals(f.id(), service.resolveActive("r1", null, "PF", "RC").id());
		assertEquals(f.id(), service.resolveActive("r1", "rk", "XX", "YY").id()); // flowKey wins, userType/context ignored
		assertEquals(FlowException.Kind.NOT_FOUND, assertThrows(FlowException.class,
				() -> service.resolveActive("r2", "rk", null, null)).kind());
		assertEquals(FlowException.Kind.NOT_FOUND, assertThrows(FlowException.class,
				() -> service.resolveActive("r2", null, "PF", "RC")).kind());
	}

	@Test
	void moreThanOneActiveForSelectorIsInvalidConfigurationWithoutTieBreak() {
		var one = service.createDraft("amb", "amb", cmd("a1", "AC"));
		var two = service.createDraft("amb", "amb", cmd("a2", "AC"));
		var ambiguous = org.mockito.Mockito.mock(FlowRepository.class);
		org.mockito.Mockito.when(ambiguous.findActive("amb", null, "PF", "AC")).thenReturn(java.util.List.of(one, two));
		var svc = new FlowService(ambiguous, new GraphValidator(t -> false));
		assertEquals(FlowException.Kind.INVALID_CONFIGURATION, assertThrows(FlowException.class,
				() -> svc.resolveActive("amb", null, "PF", "AC")).kind());
	}

}
