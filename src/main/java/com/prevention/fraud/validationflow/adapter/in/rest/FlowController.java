package com.prevention.fraud.validationflow.adapter.in.rest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.prevention.fraud.validationflow.application.FlowService;
import com.prevention.fraud.validationflow.config.TenantPrincipal;
import com.prevention.fraud.validationflow.domain.FlowDefinition;
import com.prevention.fraud.validationflow.domain.InputField;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/v1/flows")
class FlowController {

	record InputFieldRequest(@NotBlank String name, @NotBlank String type, boolean required) {
	}

	record CreateFlowRequest(@NotBlank String flowKey, @NotBlank String userType, @NotBlank String context,
			@NotBlank String displayName, String description, @NotNull Map<String, Object> graphDefinition,
			Map<String, Object> metadata, @Valid List<InputFieldRequest> inputContract) {
	}

	record FlowResponse(UUID id, String flowKey, int version, String status, String userType, String context,
			String displayName, String description, Map<String, Object> graphDefinition,
			List<InputField> inputContract, Map<String, Object> metadata, String createdBy, Instant createdAt) {
	}

	private final FlowService service;

	FlowController(FlowService service) {
		this.service = service;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	FlowResponse create(@AuthenticationPrincipal TenantPrincipal principal, @Valid @RequestBody CreateFlowRequest r) {
		List<InputField> contract = r.inputContract() == null ? List.of()
				: r.inputContract().stream().map(f -> new InputField(f.name(), f.type(), f.required())).toList();
		// ponytail: principal carries only the tenant, so createdBy is the tenant until credentials have a subject
		FlowDefinition f = service.createDraft(principal.tenantId(), principal.tenantId(),
				new FlowService.CreateFlow(r.flowKey(), r.userType(), r.context(), r.displayName(),
						r.description(), r.graphDefinition(), contract, r.metadata()));
		return new FlowResponse(f.id(), f.flowKey(), f.version(), f.status().name(), f.userType(), f.context(),
				f.displayName(), f.description(), f.graphDefinition(), f.inputContract(), f.metadata(),
				f.createdBy(), f.createdAt());
	}

}
