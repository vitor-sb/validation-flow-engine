package com.prevention.fraud.validationflow.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.prevention.fraud.validationflow.domain.FlowDefinition;
import com.prevention.fraud.validationflow.domain.FlowStatus;
import com.prevention.fraud.validationflow.domain.GraphValidator;
import com.prevention.fraud.validationflow.domain.InputField;

public class FlowService {

	public record CreateFlow(String flowKey, String userType, String context, String displayName,
			String description, Map<String, Object> graphDefinition, List<InputField> inputContract,
			Map<String, Object> metadata) {
	}

	private final FlowRepository repository;

	private final GraphValidator graphValidator;

	public FlowService(FlowRepository repository, GraphValidator graphValidator) {
		this.repository = repository;
		this.graphValidator = graphValidator;
	}

	// ponytail: version = max+1 read then insert; a concurrent create of the same key hits the unique constraint (409 via RestExceptionHandler). Retry server-side if clients need it hidden.
	public FlowDefinition createDraft(String tenantId, String createdBy, CreateFlow c) {
		return repository.save(new FlowDefinition(UUID.randomUUID(), tenantId, c.flowKey(),
				repository.nextVersion(tenantId, c.flowKey()), FlowStatus.DRAFT, c.userType(), c.context(),
				c.displayName(), c.description(), c.graphDefinition(), c.inputContract(), c.metadata(),
				createdBy, Instant.now()));
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
