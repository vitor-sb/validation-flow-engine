package com.prevention.fraud.validationflow.adapter.in.rest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.prevention.fraud.validationflow.application.execution.ProviderCallAdminService;
import com.prevention.fraud.validationflow.application.provider.ProviderCall;
import com.prevention.fraud.validationflow.config.security.TenantPrincipal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/api/v1/provider-calls")
@Tag(name = "Provider Calls")
class ProviderCallController {

	/** Never carries the callback token (or its hash) or any provider body. */
	record ProviderCallResponse(UUID id, UUID executionId, String nodeId, String providerKey, String mode, String status,
			int attempts, String reason, Instant deadline, Instant createdAt, Instant updatedAt) {
	}

	record Page(List<ProviderCallResponse> items, int page, int size, long total) {
	}

	private final ProviderCallAdminService service;

	ProviderCallController(ProviderCallAdminService service) {
		this.service = service;
	}

	@GetMapping
	@Operation(summary = "List provider calls by status", description = "Tenant's calls, newest first, paginated (`size` 1..100). Use `status=DEAD` for the dead letter.", security = @SecurityRequirement(name = "apiKey", scopes = "provider:admin"))
	@ApiResponse(responseCode = "400", ref = "#/components/responses/BadRequest")
	Page list(@AuthenticationPrincipal TenantPrincipal principal, @RequestParam ProviderCall.Status status,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return new Page(service.list(principal.tenantId(), status, page, size).stream().map(ProviderCallController::toResponse).toList(),
				page, size, service.count(principal.tenantId(), status));
	}

	@PostMapping("/{id}/retry")
	@Operation(summary = "Retry a dead provider call", description = "Puts a DEAD call back in PENDING with `attempts` reset. 409 unless the call is DEAD and its execution is still WAITING at that node.", security = @SecurityRequirement(name = "apiKey", scopes = "provider:admin"))
	@ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound")
	@ApiResponse(responseCode = "409", ref = "#/components/responses/Conflict")
	ProviderCallResponse retry(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID id) {
		return toResponse(service.retry(principal.tenantId(), id));
	}

	private static ProviderCallResponse toResponse(ProviderCall c) {
		return new ProviderCallResponse(c.id(), c.executionId(), c.nodeId(), c.providerKey(), c.mode().name(),
				c.status().name(), c.attempts(), c.reason(), c.deadline(), c.createdAt(), c.updatedAt());
	}

}
