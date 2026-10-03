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
		if (!errors.isEmpty()) {
			throw FlowException.invalid(errors);
		}
		if (!repository.activate(tenantId, id)) {
			throw FlowException.conflict("flow is no longer a draft");
		}
		return get(tenantId, id);
	}

	public FlowDefinition archive(String tenantId, UUID id) {
		get(tenantId, id);
		if (!repository.archive(tenantId, id)) {
			throw FlowException.conflict("flow is already archived");
		}
		return get(tenantId, id);
	}

}
