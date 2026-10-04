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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.prevention.fraud.validationflow.application.ExecutionService;
import com.prevention.fraud.validationflow.config.TenantPrincipal;
import com.prevention.fraud.validationflow.domain.FlowExecution;
import com.prevention.fraud.validationflow.domain.NodeExecution;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/v1/executions")
class ExecutionController {

	record StartRequest(@NotBlank String userType, @NotBlank String context, @NotNull Map<String, Object> inputData,
			String flowKey, String correlationId) {
	}

	record ExecutionResponse(UUID executionId, String flowKey, int flowVersion, String status, Instant startedAt,
			Instant completedAt, Map<String, Object> result, Map<String, Object> error) {
	}

	private final ExecutionService service;

	ExecutionController(ExecutionService service) {
		this.service = service;
	}

	record Page(List<ExecutionResponse> items, int page, int size, long total) {
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	ExecutionResponse start(@AuthenticationPrincipal TenantPrincipal principal, @Valid @RequestBody StartRequest r) {
		return toResponse(service.execute(principal.tenantId(), r.flowKey(), r.userType(), r.context(),
				r.inputData(), r.correlationId()));
	}

	@GetMapping("/{id}")
	ExecutionResponse get(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID id) {
		return toResponse(service.get(principal.tenantId(), id));
	}

	@GetMapping("/{id}/nodes")
	List<NodeExecution> nodes(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID id) {
		return service.nodes(principal.tenantId(), id);
	}

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
