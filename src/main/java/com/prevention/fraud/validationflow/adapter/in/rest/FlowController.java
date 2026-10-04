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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

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
@Tag(name = "Flow Management")
class FlowController {

	record InputFieldRequest(@NotBlank @Schema(example = "cpf") String name, @NotBlank @Schema(example = "string") String type, boolean required) {
	}

	record CreateFlowRequest(@NotBlank @Schema(example = "onboarding") String flowKey, @NotBlank @Schema(example = "PF") String userType,
			@NotBlank @Schema(example = "ONBOARDING") String context, @NotBlank @Schema(example = "PF onboarding") String displayName,
			@Schema(example = "Validates a new individual customer") String description,
			@NotNull @Schema(description = "Graph: `startNodeId` + `nodes` map (type, dynamic `config`, `transitions`)", example = "{\"startNodeId\":\"start\",\"nodes\":{\"start\":{\"type\":\"START\",\"transitions\":[{\"to\":\"end\"}]},\"end\":{\"type\":\"END\"}}}") Map<String, Object> graphDefinition,
			@Schema(description = "Free-form metadata", example = "{\"owner\":\"risk-team\"}") Map<String, Object> metadata, @Valid List<InputFieldRequest> inputContract) {
	}

	record FlowResponse(UUID id, @Schema(example = "onboarding") String flowKey, @Schema(example = "1") int version,
			@Schema(description = "Lifecycle status", allowableValues = {"DRAFT", "ACTIVE", "ARCHIVED"}) String status,
			@Schema(example = "PF") String userType, @Schema(example = "ONBOARDING") String context,
			@Schema(example = "PF onboarding") String displayName, String description,
			@Schema(description = "Graph: `startNodeId` + `nodes` map (type, dynamic `config`, `transitions`)", example = "{\"startNodeId\":\"start\",\"nodes\":{\"start\":{\"type\":\"START\",\"transitions\":[{\"to\":\"end\"}]},\"end\":{\"type\":\"END\"}}}") Map<String, Object> graphDefinition,
			List<InputField> inputContract, @Schema(description = "Free-form metadata", example = "{\"owner\":\"risk-team\"}") Map<String, Object> metadata, String createdBy, Instant createdAt) {
	}

	record UpdateFlowRequest(@NotBlank @Schema(example = "PF") String userType, @NotBlank @Schema(example = "ONBOARDING") String context,
			@NotBlank @Schema(example = "PF onboarding") String displayName,
			String description, @NotNull @Schema(description = "Graph: `startNodeId` + `nodes` map (type, dynamic `config`, `transitions`)", example = "{\"startNodeId\":\"start\",\"nodes\":{\"start\":{\"type\":\"START\",\"transitions\":[{\"to\":\"end\"}]},\"end\":{\"type\":\"END\"}}}") Map<String, Object> graphDefinition, @Schema(description = "Free-form metadata", example = "{\"owner\":\"risk-team\"}") Map<String, Object> metadata,
			@Valid List<InputFieldRequest> inputContract) {
	}

	record ValidateRequest(@NotNull @Schema(description = "Graph: `startNodeId` + `nodes` map (type, dynamic `config`, `transitions`)", example = "{\"startNodeId\":\"start\",\"nodes\":{\"start\":{\"type\":\"START\",\"transitions\":[{\"to\":\"end\"}]},\"end\":{\"type\":\"END\"}}}") Map<String, Object> graphDefinition) {
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
	@Operation(summary = "Validate a graph", description = "Checks a graph definition without persisting anything. Always 200; `valid=false` lists the problems.", security = @SecurityRequirement(name = "apiKey", scopes = "flow:write"))
	@ApiResponse(responseCode = "400", ref = "#/components/responses/BadRequest")
	ValidateResponse validate(@Valid @RequestBody ValidateRequest r) {
		List<GraphValidator.GraphError> errors = graphValidator.validate(r.graphDefinition());
		return new ValidateResponse(errors.isEmpty(), errors);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Create a flow (DRAFT)", description = "Creates a DRAFT; `version` is max+1 for the tenant and flowKey. Tenant comes from the API key.", security = @SecurityRequirement(name = "apiKey", scopes = "flow:write"))
	@ApiResponse(responseCode = "400", ref = "#/components/responses/BadRequest")
	@ApiResponse(responseCode = "409", ref = "#/components/responses/Conflict")
	FlowResponse create(@AuthenticationPrincipal TenantPrincipal principal, @Valid @RequestBody CreateFlowRequest r) {
		// ponytail: principal carries only the tenant, so createdBy is the tenant until credentials have a subject
		return toResponse(service.createDraft(principal.tenantId(), principal.tenantId(),
				new FlowService.CreateFlow(r.flowKey(), r.userType(), r.context(), r.displayName(),
						r.description(), r.graphDefinition(), contract(r.inputContract()), r.metadata())));
	}

	record PageResponse(List<FlowResponse> items, int page, int size, long total) {
	}

	@Operation(summary = "List flows", description = "Tenant's flows, newest first; filters are optional. `size` is capped at 100.", security = @SecurityRequirement(name = "apiKey", scopes = "flow:read"))
	@ApiResponse(responseCode = "400", ref = "#/components/responses/BadRequest")
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
	@Operation(summary = "Get a flow", description = "Another tenant's id returns 404.", security = @SecurityRequirement(name = "apiKey", scopes = "flow:read"))
	@ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound")
	FlowResponse get(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID id) {
		return toResponse(service.get(principal.tenantId(), id));
	}

	@PutMapping("/{id}")
	@Operation(summary = "Update a flow", description = "DRAFT: edited in place. ACTIVE: a new DRAFT version is created. ARCHIVED: 409.", security = @SecurityRequirement(name = "apiKey", scopes = "flow:write"))
	@ApiResponse(responseCode = "400", ref = "#/components/responses/BadRequest")
	@ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound")
	@ApiResponse(responseCode = "409", ref = "#/components/responses/Conflict")
	FlowResponse update(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID id,
			@Valid @RequestBody UpdateFlowRequest r) {
		return toResponse(service.update(principal.tenantId(), principal.tenantId(), id,
				new FlowService.CreateFlow(null, r.userType(), r.context(), r.displayName(), r.description(),
						r.graphDefinition(), contract(r.inputContract()), r.metadata())));
	}

	@PatchMapping("/{id}/activate")
	@Operation(summary = "Activate a DRAFT", description = "Validates the graph (422 INVALID_FLOW when invalid) and atomically archives the currently ACTIVE version of the same userType/context.", security = @SecurityRequirement(name = "apiKey", scopes = "flow:activate"))
	@ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound")
	@ApiResponse(responseCode = "409", ref = "#/components/responses/Conflict")
	@ApiResponse(responseCode = "422", ref = "#/components/responses/Unprocessable")
	FlowResponse activate(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID id) {
		return toResponse(service.activate(principal.tenantId(), id));
	}

	@PatchMapping("/{id}/archive")
	@Operation(summary = "Archive a flow", description = "Moves the flow to ARCHIVED.", security = @SecurityRequirement(name = "apiKey", scopes = "flow:activate"))
	@ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound")
	@ApiResponse(responseCode = "409", ref = "#/components/responses/Conflict")
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
