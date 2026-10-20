package com.prevention.fraud.validationflow.application.flow;

import com.prevention.fraud.validationflow.application.flow.ports.FlowRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.prevention.fraud.validationflow.application.provider.DataProvider;
import com.prevention.fraud.validationflow.application.provider.OperationSpec;
import com.prevention.fraud.validationflow.application.provider.ProviderConfigService;
import com.prevention.fraud.validationflow.application.validator.ValidatorRegistry;
import com.prevention.fraud.validationflow.application.validator.ValidatorStrategy;
import com.prevention.fraud.validationflow.domain.flow.FlowDefinition;
import com.prevention.fraud.validationflow.domain.flow.FlowStatus;
import com.prevention.fraud.validationflow.domain.flow.GraphValidator;
import com.prevention.fraud.validationflow.domain.flow.InputField;

public class FlowService {

	public record CreateFlow(String flowKey, String userType, String context, String displayName,
			String description, Map<String, Object> graphDefinition, List<InputField> inputContract,
			Map<String, Object> metadata) {
	}

	private final FlowRepository repository;

	private final GraphValidator graphValidator;

	private final ValidatorRegistry validators;

	private final ProviderConfigService providers;

	public FlowService(FlowRepository repository, GraphValidator graphValidator) {
		this(repository, graphValidator, null, null);
	}

	private final Map<String, DataProvider> dataProviders;

	public FlowService(FlowRepository repository, GraphValidator graphValidator, ValidatorRegistry validators,
			ProviderConfigService providers) {
		this(repository, graphValidator, validators, providers, Map.of());
	}

	public FlowService(FlowRepository repository, GraphValidator graphValidator, ValidatorRegistry validators,
			ProviderConfigService providers, Map<String, DataProvider> dataProviders) {
		this.dataProviders = dataProviders;
		this.repository = repository;
		this.graphValidator = graphValidator;
		this.validators = validators;
		this.providers = providers;
	}

	/** One PROVIDER_DISABLED entry per node whose validator is backed by a provider that is disabled for the tenant. */
	@SuppressWarnings("unchecked")
	public List<GraphValidator.GraphError> disabledProviders(String tenantId, Map<String, Object> graph) {
		List<GraphValidator.GraphError> out = new java.util.ArrayList<>();
		if (validators == null || !(graph.get("nodes") instanceof Map<?, ?> nodes)) {
			return out;
		}
		((Map<String, Map<String, Object>>) nodes).forEach((nodeId, node) -> {
			if (node.get("config") instanceof Map<?, ?> cfg && (cfg.get("validatorType") instanceof String
					|| "EXTERNAL_CALL".equals(node.get("type")))) {
				Optional<String> providerKey = "EXTERNAL_CALL".equals(node.get("type"))
						? Optional.ofNullable((String) cfg.get("providerKey"))
						: validators.find((String) cfg.get("validatorType")).map(ValidatorStrategy::providerKey);
				providerKey
						.filter(key -> providers.resolve(tenantId, key).isEmpty())
						.ifPresent(key -> out.add(new GraphValidator.GraphError("PROVIDER_DISABLED", nodeId,
								"provider " + key + " is disabled for this tenant")));
			}
		});
		return out;
	}

	/**
	 * OPERATION_REQUIRED / UNKNOWN_OPERATION / MISSING_PARAMETER / UNKNOWN_PARAMETER for provider-backed nodes. A provider
	 * with one operation makes {@code operation} optional; with several it is required and never falls back to default.
	 * The node's {@code query} lives in {@code config.params.query} (VALIDATION) or {@code config.query} (EXTERNAL_CALL).
	 */
	@SuppressWarnings("unchecked")
	public List<GraphValidator.GraphError> operationProblems(Map<String, Object> graph) {
		List<GraphValidator.GraphError> out = new java.util.ArrayList<>();
		if (validators == null || !(graph.get("nodes") instanceof Map<?, ?> nodes)) {
			return out;
		}
		((Map<String, Map<String, Object>>) nodes).forEach((nodeId, node) -> {
			if (!(node.get("config") instanceof Map<?, ?> rawCfg)) {
				return;
			}
			Map<String, Object> cfg = (Map<String, Object>) rawCfg;
			boolean external = "EXTERNAL_CALL".equals(node.get("type"));
			Optional<String> key = external ? Optional.ofNullable((String) cfg.get("providerKey"))
					: Optional.ofNullable((String) cfg.get("validatorType")).flatMap(validators::find)
							.map(ValidatorStrategy::providerKey);
			DataProvider provider = key.map(dataProviders::get).orElse(null);
			if (provider == null) {
				return;
			}
			Object queryCfg = external ? cfg.get("query")
					: cfg.get("params") instanceof Map<?, ?> p ? p.get("query") : null;
			operationProblem(provider, cfg.get("operation"), queryCfg instanceof Map<?, ?> q ? q.keySet() : java.util.Set.of())
					.ifPresent(m -> out.add(new GraphValidator.GraphError(m.substring(0, m.indexOf(' ')), nodeId,
							m.substring(m.indexOf(' ') + 1))));
		});
		return out;
	}

	/** "CODE message" for the first problem, or empty. */
	private static Optional<String> operationProblem(DataProvider provider, Object requested, java.util.Set<?> params) {
		List<OperationSpec> ops = provider.operations();
		List<String> keys = ops.stream().map(OperationSpec::key).toList();
		OperationSpec op;
		if (requested == null) {
			if (ops.size() != 1) {
				return Optional.of("OPERATION_REQUIRED provider " + provider.key() + " has several operations: " + keys);
			}
			op = ops.get(0);
		}
		else {
			op = ops.stream().filter(o -> o.key().equals(requested)).findFirst().orElse(null);
			if (op == null) {
				return Optional.of("UNKNOWN_OPERATION provider " + provider.key() + " has no operation '" + requested
						+ "', valid: " + keys);
			}
		}
		if (op.anyParams()) {
			return Optional.empty();
		}
		for (String r : op.required()) {
			if (!params.contains(r)) {
				return Optional.of("MISSING_PARAMETER operation " + op.key() + " requires parameter '" + r + "'");
			}
		}
		for (Object p : params) {
			if (!op.required().contains(p) && !op.optional().contains(p)) {
				return Optional.of("UNKNOWN_PARAMETER operation " + op.key() + " does not accept parameter '" + p + "'");
			}
		}
		return Optional.empty();
	}

	// ponytail: version = max+1 read then insert; a concurrent create of the same key hits the unique constraint (409 via RestExceptionHandler). Retry server-side if clients need it hidden.
	public FlowDefinition createDraft(String tenantId, String createdBy, CreateFlow c) {
		rejectOversized(c);
		return repository.save(new FlowDefinition(UUID.randomUUID(), tenantId, c.flowKey(),
				repository.nextVersion(tenantId, c.flowKey()), FlowStatus.DRAFT, c.userType(), c.context(),
				c.displayName(), c.description(), c.graphDefinition(), c.inputContract(), c.metadata(),
				createdBy, Instant.now()));
	}

	private void rejectOversized(CreateFlow c) {
		List<GraphValidator.GraphError> errors = graphValidator.checkSize(c.graphDefinition());
		if (!errors.isEmpty()) {
			throw FlowException.invalid(errors);
		}
	}

	public FlowDefinition get(String tenantId, UUID id) {
		return repository.findById(tenantId, id).orElseThrow(FlowException::notFound);
	}

	/** Resolves the single ACTIVE version by flowKey when given, else by (userType, context). No tie-breaking. */
	public FlowDefinition resolveActive(String tenantId, String flowKey, String userType, String context) {
		List<FlowDefinition> active = repository.findActive(tenantId, flowKey, userType, context);
		if (active.isEmpty()) {
			throw FlowException.notFound();
		}
		if (active.size() > 1) {
			throw FlowException.invalidConfiguration("more than one ACTIVE flow for the selector");
		}
		return active.get(0);
	}

	public FlowRepository.Page<FlowDefinition> list(String tenantId, String flowKey, FlowStatus status,
			String userType, String context, int page, int size) {
		return repository.list(tenantId, flowKey, status, userType, context, Math.max(page, 0),
				Math.min(Math.max(size, 1), 100));
	}

	/** DRAFT is edited in place; ACTIVE spawns a new DRAFT version; ARCHIVED is immutable. flowKey comes from the stored flow. */
	public FlowDefinition update(String tenantId, String updatedBy, UUID id, CreateFlow c) {
		rejectOversized(c);
		FlowDefinition f = get(tenantId, id);
		CreateFlow withKey = new CreateFlow(f.flowKey(), c.userType(), c.context(), c.displayName(),
				c.description(), c.graphDefinition(), c.inputContract(), c.metadata());
		return switch (f.status()) {
			case ACTIVE -> createDraft(tenantId, updatedBy, withKey);
			case DRAFT -> {
				FlowDefinition u = new FlowDefinition(f.id(), tenantId, f.flowKey(), f.version(), FlowStatus.DRAFT,
						c.userType(), c.context(), c.displayName(), c.description(), c.graphDefinition(),
						c.inputContract(), c.metadata(), f.createdBy(), f.createdAt());
				if (!repository.updateDraft(u)) {
					throw FlowException.conflict("flow is no longer a draft");
				}
				yield u;
			}
			case ARCHIVED -> throw FlowException.conflict("archived flow is immutable");
		};
	}

	public FlowDefinition activate(String tenantId, UUID id) {
		FlowDefinition f = get(tenantId, id);
		if (f.status() != FlowStatus.DRAFT) {
			throw FlowException.conflict("only a DRAFT can be activated");
		}
		List<GraphValidator.GraphError> errors = graphValidator.validate(f.graphDefinition());
		if (errors.isEmpty()) {
			errors = subFlowProblems(tenantId, f);
		}
		if (!errors.isEmpty()) {
			throw FlowException.invalid(errors);
		}
		List<GraphValidator.GraphError> disabled = disabledProviders(tenantId, f.graphDefinition());
		if (!disabled.isEmpty()) {
			throw FlowException.conflict("flow uses disabled providers", disabled);
		}
		List<GraphValidator.GraphError> operations = operationProblems(f.graphDefinition());
		if (!operations.isEmpty()) {
			throw FlowException.invalid(operations);
		}
		if (!repository.activate(tenantId, id)) {
			throw FlowException.conflict("flow is no longer a draft");
		}
		return get(tenantId, id);
	}

	/** Cross-flow composition: follows the ACTIVE version of each child (a child with no ACTIVE version is only checked at runtime). */
	private List<GraphValidator.GraphError> subFlowProblems(String tenantId, FlowDefinition f) {
		List<GraphValidator.GraphError> errors = new java.util.ArrayList<>();
		subFlowProblems(tenantId, List.of(f.flowKey()), f.graphDefinition(), null, errors);
		return errors;
	}

	@SuppressWarnings("unchecked")
	private void subFlowProblems(String tenantId, List<String> path, Map<String, Object> graph, String topNode,
			List<GraphValidator.GraphError> errors) {
		((Map<String, Map<String, Object>>) graph.get("nodes")).forEach((nodeId, node) -> {
			Map<String, Object> cfg = node.get("config") == null ? Map.of() : (Map<String, Object>) node.get("config");
			if (!"SUB_FLOW".equals(node.get("type")) || !(cfg.get("flowKey") instanceof String key)) {
				return;
			}
			String at = topNode == null ? nodeId : topNode;
			int limit = cfg.get("maxDepth") instanceof Integer d ? d : GraphValidator.MAX_SUB_FLOW_DEPTH;
			if (path.contains(key)) {
				errors.add(new GraphValidator.GraphError("SUB_FLOW_CYCLE_DETECTED", at, "composition cycle: " + path + " -> " + key));
			}
			else if (path.size() > limit) {
				errors.add(new GraphValidator.GraphError("SUB_FLOW_DEPTH_EXCEEDED", at,
						"sub-flow " + key + " would run at depth " + path.size() + ", maxDepth is " + limit));
			}
			else {
				List<String> next = new java.util.ArrayList<>(path);
				next.add(key);
				repository.findActive(tenantId, key, null, null).stream().limit(1)
						.forEach(c -> subFlowProblems(tenantId, next, c.graphDefinition(), at, errors));
			}
		});
	}

	public FlowDefinition archive(String tenantId, UUID id) {
		get(tenantId, id);
		if (!repository.archive(tenantId, id)) {
			throw FlowException.conflict("flow is already archived");
		}
		return get(tenantId, id);
	}

}
