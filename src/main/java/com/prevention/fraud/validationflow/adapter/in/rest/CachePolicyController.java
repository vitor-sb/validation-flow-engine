package com.prevention.fraud.validationflow.adapter.in.rest;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.prevention.fraud.validationflow.application.provider.CachePolicyService;
import com.prevention.fraud.validationflow.application.provider.DataProvider;
import com.prevention.fraud.validationflow.application.provider.ProviderCachePolicy;
import com.prevention.fraud.validationflow.config.security.TenantPrincipal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/providers/{providerKey}/cache-policies")
@Tag(name = "Provider Cache Policy")
class CachePolicyController {

	record PolicyRequest(@NotBlank @Schema(allowableValues = { "LOW_VOLATILITY", "MEDIUM_VOLATILITY", "HIGH_VOLATILITY",
			"REAL_TIME", "CUSTOM" }) String dataCategory,
			@Schema(description = "Optional for the default categories (their configured TTL applies); required for CUSTOM") Integer ttlSeconds,
			Boolean fallbackOnErrorAllowed, @Size(max = 500) String justification, @Size(max = 200) String owner,
			@Schema(description = "ISO date, e.g. 2027-01-31") String reviewDate) {
	}

	record PolicyResponse(String operation, String dataCategory, Integer ttlSeconds, boolean fallbackOnErrorAllowed,
			String justification, String owner, String reviewDate) {
	}

	private final CachePolicyService service;

	private final Set<String> knownKeys;

	CachePolicyController(CachePolicyService service, List<DataProvider> providers) {
		this.service = service;
		this.knownKeys = providers.stream().map(DataProvider::key).collect(Collectors.toSet());
	}

	@GetMapping
	@Operation(summary = "List the provider's cache policies", description = "Tenant's rows only. `ttlSeconds` is the stored value (null = category default); the effective TTL is also capped by `app.providers.cache-max-ttl`.", security = @SecurityRequirement(name = "apiKey", scopes = "provider:admin"))
	@ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound")
	List<PolicyResponse> list(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable String providerKey) {
		known(providerKey);
		return service.list(principal.tenantId(), providerKey).stream().map(p -> new PolicyResponse(p.operation(),
				p.dataCategory(), p.ttlSeconds(), p.fallbackOnErrorAllowed(), p.justification(), p.owner(), p.reviewDate() == null ? null : p.reviewDate().toString())).toList();
	}

	@PutMapping("/{operation}")
	@Operation(summary = "Create or replace the cache policy of an operation", description = "CUSTOM is accepted only with ttlSeconds, justification, owner and reviewDate (400 otherwise). Without a policy nothing is cached; REAL_TIME never caches. Records an audit entry.", security = @SecurityRequirement(name = "apiKey", scopes = "provider:admin"))
	@ApiResponse(responseCode = "400", ref = "#/components/responses/BadRequest")
	@ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound")
	PolicyResponse put(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable String providerKey,
			@PathVariable String operation, @Valid @RequestBody PolicyRequest r) {
		known(providerKey);
		try {
			var review = r.reviewDate() == null ? null : LocalDate.parse(r.reviewDate());
			service.save(new ProviderCachePolicy(principal.tenantId(), providerKey, operation, r.dataCategory(),
					r.ttlSeconds(), Boolean.TRUE.equals(r.fallbackOnErrorAllowed()), r.justification(), r.owner(), review), principal.subject());
		}
		catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
		}
		return new PolicyResponse(operation, r.dataCategory(), r.ttlSeconds(), Boolean.TRUE.equals(r.fallbackOnErrorAllowed()),
				r.justification(), r.owner(), r.reviewDate());
	}

	private void known(String providerKey) {
		if (!knownKeys.contains(providerKey)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown provider");
		}
	}

}
