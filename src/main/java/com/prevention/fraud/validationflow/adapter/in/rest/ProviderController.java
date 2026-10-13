package com.prevention.fraud.validationflow.adapter.in.rest;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
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

import com.prevention.fraud.validationflow.application.provider.DataProvider;
import com.prevention.fraud.validationflow.application.provider.ProviderConfig;
import com.prevention.fraud.validationflow.application.provider.ProviderConfigService;
import com.prevention.fraud.validationflow.config.security.TenantPrincipal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/providers")
@Tag(name = "Provider Configuration")
class ProviderController {

	record ProviderConfigRequest(boolean enabled, @Size(max = 500) @Schema(example = "https://brasilapi.com.br/api") String baseUrl,
			@Positive @Schema(example = "2000") Integer connectTimeoutMs, @Positive @Schema(example = "5000") Integer readTimeoutMs,
			@Size(max = 200) @Schema(description = "Name of the secret (e.g. an environment variable), never the secret itself", example = "BUREAU_TOKEN") String credentialsRef) {
	}

	record ProviderConfigResponse(String providerKey, boolean enabled, String baseUrl, Integer connectTimeoutMs,
			Integer readTimeoutMs, @Schema(description = "Reference to the secret; the secret is never returned") String credentialsRef,
			String updatedBy, Instant updatedAt) {
	}

	private final ProviderConfigService service;

	private final Set<String> knownKeys;

	private final boolean local;

	ProviderController(ProviderConfigService service, List<DataProvider> providers, Environment env) {
		this.service = service;
		this.knownKeys = providers.stream().map(DataProvider::key).collect(Collectors.toSet());
		this.local = env.acceptsProfiles(Profiles.of("local"));
	}

	@GetMapping
	@Operation(summary = "List provider configurations", description = "Tenant's rows only. Credentials appear as references.", security = @SecurityRequirement(name = "apiKey", scopes = "provider:read"))
	List<ProviderConfigResponse> list(@AuthenticationPrincipal TenantPrincipal principal) {
		return service.list(principal.tenantId()).stream().map(ProviderController::toResponse).toList();
	}

	@PutMapping("/{providerKey}")
	@Operation(summary = "Create or replace a provider configuration", description = "404 when no adapter is registered for `providerKey`; `baseUrl` must be https outside the `local` profile. Records `updatedBy` and an audit entry.", security = @SecurityRequirement(name = "apiKey", scopes = "provider:admin"))
	@ApiResponse(responseCode = "400", ref = "#/components/responses/BadRequest")
	@ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound")
	ProviderConfigResponse put(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable String providerKey,
			@Valid @RequestBody ProviderConfigRequest r) {
		if (!knownKeys.contains(providerKey)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown provider");
		}
		checkUrl(r.baseUrl());
		return toResponse(service.save(new ProviderConfig(principal.tenantId(), providerKey, r.enabled(), r.baseUrl(),
				r.connectTimeoutMs(), r.readTimeoutMs(), r.credentialsRef(), principal.subject(), null)));
	}

	private void checkUrl(String url) {
		if (url == null) {
			return;
		}
		String scheme;
		try {
			scheme = URI.create(url).getScheme();
		}
		catch (IllegalArgumentException e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "baseUrl is not a valid URL");
		}
		if (!"https".equals(scheme) && !(local && "http".equals(scheme))) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "baseUrl must be https");
		}
	}

	private static ProviderConfigResponse toResponse(ProviderConfig c) {
		return new ProviderConfigResponse(c.providerKey(), c.enabled(), c.baseUrl(), c.connectTimeoutMs(),
				c.readTimeoutMs(), c.credentialsRef(), c.updatedBy(), c.updatedAt());
	}

}
