package com.prevention.fraud.validationflow.application.execution.node;

import com.prevention.fraud.validationflow.application.execution.ExecutionService;
import com.prevention.fraud.validationflow.application.validator.ValidatorRegistry;
import com.prevention.fraud.validationflow.application.validator.ValidatorStrategy;
import com.prevention.fraud.validationflow.domain.flow.DocumentGroups;
import com.prevention.fraud.validationflow.domain.execution.FlowExecution;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The built-in handlers for START, DECISION, VALIDATION, SUB_FLOW and END. */
public final class NodeHandlers {

	private NodeHandlers() {
	}

	public static List<NodeHandler> defaults(ValidatorRegistry registry, ValidatorRunner runner, SubFlowRunner subFlows) {
		return List.of(new Start(), new Decision(), new Validation(registry, runner), new SubFlow(subFlows), new End());
	}

	private static void complete(NodeStep s, Map<String, Object> out, Instant started) {
		s.host().recorder().recordNode(s.ex(), s.nodeId(), s.type(), 1, "COMPLETED", out, null, s.ctx(), started);
	}

	public record Start() implements NodeHandler {

		public String type() {
			return "START";
		}

		public FlowExecution handle(NodeStep s) {
			complete(s, Map.of(), Instant.now());
			return null;
		}

	}

	public record Decision() implements NodeHandler {

		public String type() {
			return "DECISION";
		}

		public FlowExecution handle(NodeStep s) {
			var groups = s.config().documentGroups();
			Map<String, Object> out = groups == null ? Map.of() : DocumentGroups.resolve(groups, s.ctx());
			if (groups != null) {
				s.outputs().put(s.nodeId(), out);
			}
			complete(s, out, Instant.now());
			return null;
		}

	}

	public record Validation(ValidatorRegistry registry, ValidatorRunner runner) implements NodeHandler {

		public String type() {
			return "VALIDATION";
		}

		public FlowExecution handle(NodeStep s) {
			ValidatorStrategy v = registry.find(s.config().validatorType()).orElse(null);
			if (v == null) {
				return s.fail("VALIDATOR_NOT_FOUND", "validator not registered at node " + s.nodeId());
			}
			var a = runner.run(s.nodeId(), v, s.ctx(), s.config(), (att, st, out, err, t) -> s.host().recorder()
					.recordNode(s.ex(), s.nodeId(), s.type(), att, st, out, err, s.ctx(), t));
			if (a.error() != null) {
				return s.host().save(s.ex().fail(s.ctx(), (String) a.error().get("code"),
						(String) a.error().get("message"), (Boolean) a.error().get("retryable"),
						Map.of("nodeId", s.nodeId(), "attempts", a.attempts())));
			}
			Map<String, Object> out = new LinkedHashMap<>(a.result().output() == null ? Map.of() : a.result().output());
			out.put("success", a.result().success());
			s.outputs().put(s.nodeId(), out);
			return null;
		}

	}

	public record SubFlow(SubFlowRunner subFlows) implements NodeHandler {

		public String type() {
			return "SUB_FLOW";
		}

		public FlowExecution handle(NodeStep s) {
			Instant started = Instant.now();
			var r = subFlows.run(s.ex(), s.nodeId(), s.config(), s.ctx(), s.chain(), s.host()::run);
			if (r.code() != null) {
				s.host().recorder().recordNode(s.ex(), s.nodeId(), s.type(), 1, "FAILED", Map.of(),
						ExecutionService.error(r.code(), r.message(), false), s.ctx(), started);
				return s.host().save(s.ex().fail(s.ctx(), r.code(), r.message(), false, r.details()));
			}
			s.outputs().put(s.nodeId(), r.output());
			complete(s, r.output(), started);
			return null;
		}

	}

	public record End() implements NodeHandler {

		public String type() {
			return "END";
		}

		public FlowExecution handle(NodeStep s) {
			complete(s, Map.of(), Instant.now());
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("endNodeId", s.nodeId());
			result.put("config", s.config().raw());
			result.put("nodes", s.ctx().get("nodes"));
			return s.host().save(s.ex().complete(s.ctx(), result));
		}

	}

}
