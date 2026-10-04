package com.prevention.fraud.validationflow.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.OpenApiCustomizer;

/**
 * OpenAPI metadata. The committed {@code openapi.yaml} is generated from this + the controller annotations
 * (see OpenApiContractTest, which fails when the file is stale).
 */
@Configuration
class OpenApiConfig {

	static final String API_KEY = "apiKey";

	@Bean
	OpenAPI openApi() {
		return new OpenAPI()
				.info(new Info().title("Validation Flow Engine API").version("v1").description(
						"Flow definition management and validation executions. Every request except the health "
								+ "endpoints needs an `X-API-Key` header; the key fixes the tenant and its scopes. "
								+ "Operational probes are also served at `/actuator/health/liveness` and "
								+ "`/actuator/health/readiness` (public, Spring Actuator format)."))
				.servers(List.of(new Server().url("/").description("This server")))
				.tags(List.of(
						new Tag().name("Flow Management").description("Create, version, validate, activate and archive flow definitions."),
						new Tag().name("Execution").description("Run validation flows and read execution history."),
						new Tag().name("Node Types").description("Catalog of validator node types usable in a flow graph."),
						new Tag().name("Health").description("Public liveness check.")))
				.components(new Components().addSecuritySchemes(API_KEY, new SecurityScheme()
						.type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-API-Key")
						.description("Static API key. Operations list the scope they require (403 when missing).")));
	}

	/** Shared error responses (all use ErrorResponse) + 401/403 on every secured operation. */
	@Bean
	OpenApiCustomizer errorResponses() {
		return api -> {
			var c = api.getComponents();
			io.swagger.v3.core.converter.ModelConverters.getInstance()
					.readAll(com.prevention.fraud.validationflow.adapter.in.rest.ErrorResponse.class).forEach(c::addSchemas);
			c.addResponses("BadRequest", error("Invalid request (VALIDATION_ERROR, MALFORMED_REQUEST, BAD_REQUEST, INVALID_INPUT).",
					"VALIDATION_ERROR", "displayName: must not be blank"));
			c.addResponses("Unauthorized", error("Missing or invalid `X-API-Key`.", "UNAUTHORIZED", "authentication required"));
			c.addResponses("Forbidden", error("The key lacks the required scope.", "FORBIDDEN", "missing scope"));
			c.addResponses("NotFound", error("Resource not found, or it belongs to another tenant (FLOW_NOT_FOUND, EXECUTION_NOT_FOUND).",
					"FLOW_NOT_FOUND", "flow not found"));
			c.addResponses("Conflict", error("Conflict (CONFLICT): duplicate resource, wrong lifecycle state, or Idempotency-Key reused with a different payload.",
					"CONFLICT", "flow is not DRAFT"));
			c.addResponses("Unprocessable", error("The flow graph is invalid (INVALID_FLOW); details list `CODE: message` per problem.",
					"INVALID_FLOW", "MISSING_START: no start node"));
			api.getPaths().forEach((path, item) -> item.readOperations().forEach(op -> {
				if (op.getSecurity() == null || op.getSecurity().isEmpty()) {
					return; // public
				}
				op.getResponses().putIfAbsent("401", ref("Unauthorized"));
				op.getResponses().putIfAbsent("403", ref("Forbidden"));
			}));
		};
	}

	private static ApiResponse ref(String name) {
		return new ApiResponse().$ref("#/components/responses/" + name);
	}

	private static ApiResponse error(String description, String code, String detail) {
		var example = new Example().value(java.util.Map.of("code", code, "retryable", false, "details", List.of(detail)));
		return new ApiResponse().description(description).content(new Content().addMediaType("application/json",
				new MediaType().schema(new Schema<>().$ref("#/components/schemas/ErrorResponse")).addExamples("default", example)));
	}

}
