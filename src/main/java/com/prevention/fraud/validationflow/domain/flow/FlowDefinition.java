package com.prevention.fraud.validationflow.domain.flow;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record FlowDefinition(UUID id, String tenantId, String flowKey, int version, FlowStatus status,
		String userType, String context, String displayName, String description,
		Map<String, Object> graphDefinition, List<InputField> inputContract, Map<String, Object> metadata,
		String createdBy, Instant createdAt) {
}
