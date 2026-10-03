package com.prevention.fraud.validationflow.adapter.in.rest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.prevention.fraud.validationflow.application.FlowService;
import com.prevention.fraud.validationflow.config.TenantPrincipal;
import com.prevention.fraud.validationflow.domain.FlowDefinition;
import com.prevention.fraud.validationflow.domain.FlowStatus;
import com.prevention.fraud.validationflow.domain.GraphValidator;
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

	record UpdateFlowRequest(@NotBlank String userType, @NotBlank String context, @NotBlank String displayName,
			String description, @NotNull Map<String, Object> graphDefinition, Map<String, Object> metadata,
			@Valid List<InputFieldRequest> inputContract) {
	}

	record ValidateRequest(@NotNull Map<String, Object> graphDefinition) {
	}

	record ValidateResponse(boolean valid, List<GraphValidator.GraphError> errors) {
	}

	private final FlowService service;

	private final GraphValidator graphValidator;

	FlowController(FlowService service, GraphValidator graphValidator) {
		this.service = service;
		this.graphValidator = graphValidator;
	}

	@PostMapping("/validate")
	ValidateResponse validate(@Valid @RequestBody ValidateRequest r) {
		List<GraphValidator.GraphError> errors = graphValidator.validate(r.graphDefinition());
		return new ValidateResponse(errors.isEmpty(), errors);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	FlowResponse create(@AuthenticationPrincipal TenantPrincipal principal, @Valid @RequestBody CreateFlowRequest r) {
		// ponytail: principal carries only the tenant, so createdBy is the tenant until credentials have a subject
		return toResponse(service.createDraft(principal.tenantId(), principal.tenantId(),
				new FlowService.CreateFlow(r.flowKey(), r.userType(), r.context(), r.displayName(),
						r.description(), r.graphDefinition(), contract(r.inputContract()), r.metadata())));
	}

	record PageResponse(List<FlowResponse> items, int page, int size, long total) {
	}

	@GetMapping
	PageResponse list(@AuthenticationPrincipal TenantPrincipal principal, @RequestParam(required = false) String flowKey,
			@RequestParam(required = false) FlowStatus status, @RequestParam(required = false) String userType,
			@RequestParam(required = false) String context, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		var p = service.list(principal.tenantId(), flowKey, status, userType, context, page, size);
		return new PageResponse(p.items().stream().map(FlowController::toResponse).toList(), Math.max(page, 0),
				Math.min(Math.max(size, 1), 100), p.total());
	}

	@GetMapping("/{id}")
	FlowResponse get(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID id) {
		return toResponse(service.get(principal.tenantId(), id));
	}

	@PutMapping("/{id}")
	FlowResponse update(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID id,
			@Valid @RequestBody UpdateFlowRequest r) {
		return toResponse(service.update(principal.tenantId(), principal.tenantId(), id,
				new FlowService.CreateFlow(null, r.userType(), r.context(), r.displayName(), r.description(),
						r.graphDefinition(), contract(r.inputContract()), r.metadata())));
	}

	@PatchMapping("/{id}/activate")
	FlowResponse activate(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID id) {
		return toResponse(service.activate(principal.tenantId(), id));
	}

	@PatchMapping("/{id}/archive")
	FlowResponse archive(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID id) {
		return toResponse(service.archive(principal.tenantId(), id));
	}

	private static List<InputField> contract(List<InputFieldRequest> in) {
		return in == null ? List.of() : in.stream().map(f -> new InputField(f.name(), f.type(), f.required())).toList();
	}

	private static FlowResponse toResponse(FlowDefinition f) {
		return new FlowResponse(f.id(), f.flowKey(), f.version(), f.status().name(), f.userType(), f.context(),
				f.displayName(), f.description(), f.graphDefinition(), f.inputContract(), f.metadata(),
				f.createdBy(), f.createdAt());
	}

}
