package com.prevention.fraud.validationflow.adapter.in.rest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.prevention.fraud.validationflow.application.ExecutionService;
import com.prevention.fraud.validationflow.config.TenantPrincipal;
import com.prevention.fraud.validationflow.domain.FlowExecution;
import com.prevention.fraud.validationflow.domain.NodeExecution;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/executions")
@Tag(name = "Execution")
class ExecutionController {

	record StartRequest(@NotBlank @Schema(example = "PF") String userType, @NotBlank @Schema(example = "ONBOARDING") String context,
			@NotNull @Schema(description = "Dynamic input, validated against the flow's inputContract", example = "{\"cpf\":\"12345678900\"}") Map<String, Object> inputData,
			@Schema(description = "Optional: pick the ACTIVE flow by key instead of userType+context", example = "onboarding") String flowKey,
			@Size(max = 128) @Schema(example = "req-123") String correlationId) {
	}

	record ExecutionResponse(UUID executionId, @Schema(example = "onboarding") String flowKey, @Schema(example = "1") int flowVersion,
			@Schema(example = "COMPLETED") String status, Instant startedAt, Instant completedAt,
			@Schema(description = "Final context (dynamic); credential-named keys are masked", example = "{\"nodes\":{\"start\":{}}}") Map<String, Object> result,
			@Schema(description = "Error info when FAILED (dynamic)", example = "{\"code\":\"VALIDATOR_FAILED\"}") Map<String, Object> error) {
	}

	private final ExecutionService service;

	ExecutionController(ExecutionService service) {
		this.service = service;
	}

	record Page(List<ExecutionResponse> items, int page, int size, long total) {
	}

	@PostMapping
	@Operation(summary = "Start an execution", description = "Runs the ACTIVE flow synchronously. With `Idempotency-Key` (scoped to the tenant) a replay with the same payload returns the original execution, flagged with `Idempotent-Replayed: true`; a different payload returns 409.", security = @SecurityRequirement(name = "apiKey", scopes = "validation:execute"))
	@ApiResponse(responseCode = "201", description = "Execution created or replayed", headers = @Header(name = "Idempotent-Replayed", description = "`true` only when the response replays an earlier call with the same `Idempotency-Key`", schema = @Schema(type = "string", allowableValues = "true")))
	@ApiResponse(responseCode = "400", ref = "#/components/responses/BadRequest")
	@ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound")
	@ApiResponse(responseCode = "409", ref = "#/components/responses/Conflict")
	ResponseEntity<ExecutionResponse> start(@AuthenticationPrincipal TenantPrincipal principal,
			@Parameter(description = "Optional idempotency key (max 255 chars of [A-Za-z0-9._:-])", example = "order-42") @RequestHeader(name = "Idempotency-Key", required = false) @Pattern(regexp = "[A-Za-z0-9._:-]{0,255}", message = "must be at most 255 chars of [A-Za-z0-9._:-]") String idempotencyKey,
			@Valid @RequestBody StartRequest r) {
		if (idempotencyKey != null && !idempotencyKey.isBlank()) {
			var o = service.executeIdempotent(principal.tenantId(), idempotencyKey, r.flowKey(), r.userType(),
					r.context(), r.inputData(), r.correlationId());
			var created = ResponseEntity.status(HttpStatus.CREATED);
			if (o.replayed()) {
				created.header("Idempotent-Replayed", "true");
			}
			return created.body(toResponse(o.execution()));
		}
		return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(service.execute(principal.tenantId(),
				r.flowKey(), r.userType(), r.context(), r.inputData(), r.correlationId())));
	}

	@GetMapping("/{id}")
	@Operation(summary = "Get an execution", description = "Another tenant's id returns 404.", security = @SecurityRequirement(name = "apiKey", scopes = "validation:read"))
	@ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound")
	ExecutionResponse get(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID id) {
		return toResponse(service.get(principal.tenantId(), id));
	}

	@GetMapping("/{id}/nodes")
	@Operation(summary = "List node attempts", description = "One entry per node attempt, with masked snapshots.", security = @SecurityRequirement(name = "apiKey", scopes = "validation:read"))
	@ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound")
	List<NodeExecution> nodes(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID id) {
		return service.nodes(principal.tenantId(), id);
	}

	@Operation(summary = "List executions", description = "Tenant's executions, paginated (`size` 1..100).", security = @SecurityRequirement(name = "apiKey", scopes = "validation:read"))
	@ApiResponse(responseCode = "400", ref = "#/components/responses/BadRequest")
	@GetMapping
	Page list(@AuthenticationPrincipal TenantPrincipal principal, @RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return new Page(service.list(principal.tenantId(), page, size).stream().map(ExecutionController::toResponse).toList(),
				page, size, service.count(principal.tenantId()));
	}

	private static ExecutionResponse toResponse(FlowExecution e) {
		return new ExecutionResponse(e.id(), e.flowKey(), e.flowVersion(), e.status().name(), e.startedAt(),
				e.completedAt(), e.result(), e.errorInfo());
	}

}
