package com.prevention.fraud.validationflow.application.execution.node;

import com.prevention.fraud.validationflow.application.execution.ExecutionService;
import com.prevention.fraud.validationflow.application.provider.CallbackHandle;
import com.prevention.fraud.validationflow.application.provider.DataProvider;
import com.prevention.fraud.validationflow.application.provider.ProviderCache;
import com.prevention.fraud.validationflow.application.provider.ProviderCall;
import com.prevention.fraud.validationflow.application.provider.ProviderCallRepository;
import com.prevention.fraud.validationflow.application.provider.ProviderConfigService;
import com.prevention.fraud.validationflow.application.provider.ProviderException;
import com.prevention.fraud.validationflow.application.provider.ProviderResult;
import com.prevention.fraud.validationflow.application.validator.ProviderBackedValidator;
import com.prevention.fraud.validationflow.application.validator.ValidatorException;
import com.prevention.fraud.validationflow.application.validator.ValidatorRegistry;
import com.prevention.fraud.validationflow.application.validator.ValidatorStrategy;
import com.prevention.fraud.validationflow.domain.flow.DocumentGroups;
import com.prevention.fraud.validationflow.domain.execution.FlowExecution;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** The built-in handlers for START, DECISION, VALIDATION, SUB_FLOW and END. */
public final class NodeHandlers {

	private NodeHandlers() {
	}

	public static List<NodeHandler> defaults(ValidatorRegistry registry, ValidatorRunner runner, SubFlowRunner subFlows) {
		return List.of(new Start(), new Decision(), new Validation(registry, runner), new SubFlow(subFlows), new HumanApproval(), new End());
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
			var a = runner.run(s.ex().tenantId(), s.nodeId(), v, s.ctx(), s.config(), (att, st, out, err, t) -> s.host().recorder()
					.recordNode(s.ex(), s.nodeId(), s.type(), att, st, out, err, s.ctx(), t));
			if (a.error() != null && Boolean.TRUE.equals(a.error().get("retryable"))) {
				// retries exhausted: a policy-approved stale answer replaces the failure, flagged in the output
				var stale = v.fallback(new ValidatorStrategy.ValidationInput(Map.copyOf(s.ctx()), s.config().raw(),
						s.ex().tenantId()), (String) a.error().get("code"));
				if (stale.isPresent()) {
					Map<String, Object> out = new LinkedHashMap<>(stale.get().output());
					out.put("success", stale.get().success());
					s.outputs().put(s.nodeId(), out);
					s.host().recorder().recordNode(s.ex(), s.nodeId(), s.type(), a.attempts() + 1, "COMPLETED", out, null,
							s.ctx(), Instant.now());
					return null;
				}
			}
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
			if (r.waitingChild() != null) {
				var child = r.waitingChild();
				return s.pause("SUB_FLOW_WAITING", child.waitDeadline(), Map.of("childExecutionId", child.id().toString()));
			}
			s.outputs().put(s.nodeId(), r.output());
			complete(s, r.output(), started);
			return null;
		}

	}

	/** Parks the execution until a person decides; the deadline was already validated against {@code app.limits.max-wait}. */
	public record HumanApproval() implements NodeHandler {

		public String type() {
			return "HUMAN_APPROVAL";
		}

		public FlowExecution handle(NodeStep s) {
			Map<String, Object> raw = s.config().raw();
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("reason", raw.get("reason"));
			if (raw.get("assignedGroup") != null) {
				details.put("assignedGroup", raw.get("assignedGroup"));
			}
			Instant deadline = Instant.now().plus(Duration.parse((String) raw.get("deadline")));
			details.put("deadline", deadline.toString());
			return s.pause((String) raw.get("reason"), deadline, details);
		}

	}

	/**
	 * Calls an async-capable provider without blocking: {@code Done} continues like a VALIDATION node, {@code Pending}
	 * records a {@code provider_call} and parks the execution until the answer arrives (callback or polling).
	 */
	public record ExternalCall(Map<String, DataProvider> providers, ProviderConfigService configs,
			ProviderCallRepository calls, ProviderCache cache) implements NodeHandler {

		public String type() {
			return "EXTERNAL_CALL";
		}

		public FlowExecution handle(NodeStep s) {
			Map<String, Object> raw = s.config().raw();
			String key = (String) raw.get("providerKey");
			DataProvider provider = providers.get(key);
			if (provider == null) {
				return fail(s, "PROVIDER_NOT_FOUND", "provider " + key + " is not registered", false);
			}
			var config = configs.resolve(s.ex().tenantId(), key).orElse(null);
			if (config == null) {
				return fail(s, "PROVIDER_DISABLED", key + " is not enabled for this tenant", false);
			}
			ProviderResult r;
			Instant now = Instant.now();
			Map<String, Object> query;
			ProviderCache.Lookup lookup;
			String operation = ProviderCache.operationOf(raw);
			try {
				query = ProviderBackedValidator.query(s.nodeId(), raw.get("query"), Maps.of(s.ctx().get("inputData")));
				lookup = cache.lookup(s.ex().tenantId(), key, operation, query, "BYPASS".equals(raw.get("cache")), now);
			}
			catch (ValidatorException e) {
				return fail(s, e.code(), e.getMessage(), e.retryable());
			}
			if (lookup.hit()) {
				return done(s, raw, lookup.entry().response(), lookup, operation, null, now, null);
			}
			// the clear token goes to the provider only; the row keeps its hash
			var callback = new CallbackHandle(UUID.randomUUID(), ProviderCall.newToken());
			try {
				r = provider.start(config, query, callback);
			}
			catch (ProviderException e) {
				var stale = e.retryable() ? cache.staleFallback(s.ex().tenantId(), key, operation, query) : Optional.<ProviderCache.Lookup>empty();
				if (stale.isEmpty()) {
					return fail(s, e.code(), e.getMessage(), e.retryable());
				}
				return done(s, raw, stale.get().entry().response(), stale.get(), operation, null, now, e.code());
			}
			if (r instanceof ProviderResult.Done d) {
				String policy = cache.store(s.ex().tenantId(), key, operation, query, d.data(), now);
				return done(s, raw, d.data(), lookup, operation, policy, now, null);
			}
			var p = (ProviderResult.Pending) r;
			Instant deadline = now.plus(Duration.parse((String) raw.get("maxWait")));
			UUID callId = callback.callId();
			calls.save(new ProviderCall(callId, s.ex().tenantId(), s.ex().id(), s.nodeId(), key, p.externalRef(), p.mode(),
					ProviderCall.Status.PENDING, 0, p.mode() == ProviderResult.Mode.POLL ? now.plus(p.pollAfter()) : null,
					deadline, ProviderCall.hashToken(callback.token()), now, now));
			return s.pause("PROVIDER_PENDING", deadline,
					Map.of("callId", callId.toString(), "providerKey", key, "mode", p.mode().name()));
		}

		private static FlowExecution done(NodeStep s, Map<String, Object> raw, Map<String, Object> data,
				ProviderCache.Lookup lookup, String operation, String policy, Instant now, String fallbackReason) {
			Map<String, Object> out = new LinkedHashMap<>(lookup.tag(data, operation, policy, now));
			out.put("success", ProviderBackedValidator.matches(raw.get("expect"), data));
			if (fallbackReason != null) {
				out.put("fallbackReason", fallbackReason);
			}
			s.outputs().put(s.nodeId(), out);
			complete(s, out, now);
			return null;
		}

		private static FlowExecution fail(NodeStep s, String code, String message, boolean retryable) {
			s.host().recorder().recordNode(s.ex(), s.nodeId(), s.type(), 1, "FAILED", Map.of(),
					ExecutionService.error(code, message, retryable), s.ctx(), Instant.now());
			return s.host().save(s.ex().fail(s.ctx(), code, message, retryable, Map.of("nodeId", s.nodeId())));
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
