package com.prevention.fraud.validationflow.domain.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

class FlowExecutionTransitionsTest {

	static FlowExecution in(ExecutionStatus s) {
		return new FlowExecution(UUID.randomUUID(), "t", UUID.randomUUID(), "k", 1, Map.of(), null, s, Map.of(), Map.of(),
				null, null, 0, Instant.now(), null, null, null);
	}

	static final Instant DL = Instant.now().plusSeconds(60);

	static final Map<String, UnaryOperator<FlowExecution>> OPS = Map.of(
			"wait", e -> e.waitAt("n", DL, "why", Map.of()),
			"resume", FlowExecution::resume,
			"cancel", FlowExecution::cancel,
			"timeOut", e -> e.timeOut(Map.of("code", "WAIT_EXPIRED")),
			"complete", e -> e.complete(Map.of(), Map.of()),
			"fail", e -> e.fail(Map.of(), "X", "x"),
			"start", FlowExecution::start);

	@Test
	void waitingTransitions() {
		var w = in(ExecutionStatus.RUNNING).waitAt("n", DL, "why", Map.of());
		assertEquals(ExecutionStatus.WAITING, w.status());
		assertEquals("n", w.waitingNodeId());
		assertEquals(DL, w.waitDeadline());
		assertEquals("why", w.waitReason());
		assertNull(w.completedAt());

		var r = w.resume();
		assertEquals(ExecutionStatus.RUNNING, r.status());
		assertNull(r.waitingNodeId());
		assertNull(r.completedAt());

		assertEquals(ExecutionStatus.CANCELLED, w.cancel().status());
		assertEquals(ExecutionStatus.TIMED_OUT, w.timeOut(Map.of()).status());
		assertEquals(true, w.cancel().completedAt() != null);
	}

	@Test
	void everythingElseIsForbidden() {
		for (var from : ExecutionStatus.values()) {
			for (var op : new String[] { "wait", "resume", "cancel", "timeOut" }) {
				boolean ok = op.equals("wait") ? from == ExecutionStatus.RUNNING : from == ExecutionStatus.WAITING;
				if (!ok) {
					assertThrows(IllegalStateException.class, () -> OPS.get(op).apply(in(from)), from + " " + op);
				}
			}
		}
		// WAITING can only leave through resume/cancel/timeOut
		for (var op : new String[] { "wait", "complete", "fail", "start" }) {
			assertThrows(IllegalStateException.class, () -> OPS.get(op).apply(in(ExecutionStatus.WAITING)), op);
		}
	}

}
