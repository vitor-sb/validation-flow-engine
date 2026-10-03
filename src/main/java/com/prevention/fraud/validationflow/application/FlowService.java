package com.prevention.fraud.validationflow.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.prevention.fraud.validationflow.domain.FlowDefinition;
import com.prevention.fraud.validationflow.domain.FlowStatus;
import com.prevention.fraud.validationflow.domain.InputField;

public class FlowService {

	public record CreateFlow(String flowKey, String userType, String context, String displayName,
			String description, Map<String, Object> graphDefinition, List<InputField> inputContract,
			Map<String, Object> metadata) {
	}

	private final FlowRepository repository;

	public FlowService(FlowRepository repository) {
		this.repository = repository;
	}

	// ponytail: version = max+1 read then insert; a concurrent create of the same key hits the unique constraint (500). Retry if it matters.
	public FlowDefinition createDraft(String tenantId, String createdBy, CreateFlow c) {
		return repository.save(new FlowDefinition(UUID.randomUUID(), tenantId, c.flowKey(),
				repository.nextVersion(tenantId, c.flowKey()), FlowStatus.DRAFT, c.userType(), c.context(),
				c.displayName(), c.description(), c.graphDefinition(), c.inputContract(), c.metadata(),
				createdBy, Instant.now()));
	}

}
