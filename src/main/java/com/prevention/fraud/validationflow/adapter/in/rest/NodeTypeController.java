package com.prevention.fraud.validationflow.adapter.in.rest;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import com.prevention.fraud.validationflow.application.provider.DataProvider;
import com.prevention.fraud.validationflow.application.validator.ValidatorRegistry;
import com.prevention.fraud.validationflow.application.validator.ValidatorStrategy;

@RestController
@RequestMapping("/api/v1/node-types")
@Tag(name = "Node Types")
class NodeTypeController {

	record NodeType(@Schema(description = "Value to use as the node `type` in a graph", example = "DOCUMENT_CHECK") String key,
			@Schema(description = "JSON-schema-like description of the node `config` object (dynamic)",
					example = "{\"type\":\"object\",\"properties\":{\"threshold\":{\"type\":\"number\"}}}") Map<String, Object> schema,
			@Schema(description = "Declared capabilities", example = "[\"RETRY\",\"TIMEOUT\"]") Set<String> capabilities,
			@Schema(description = "Provider -> operations and parameters. A provider-backed validator lists its own provider; EXTERNAL_CALL lists all; others are empty") Map<String, List<ProviderOperation>> providers) {
	}

	record ProviderOperation(@Schema(example = "cnpj") String key, @Schema(example = "[\"cnpj\"]") Set<String> required,
			@Schema(example = "[]") Set<String> optional,
			@Schema(description = "Legacy `default` operation: accepts any parameter") boolean anyParams) {
	}

	/** Built-in node (not a validator): pauses the execution until a person decides. */
	private static final NodeType HUMAN_APPROVAL = new NodeType("HUMAN_APPROVAL", Map.of("type", "object", "required",
			List.of("reason", "deadline"), "properties", Map.of("reason", Map.of("type", "string"), "assignedGroup",
					Map.of("type", "string"), "deadline", Map.of("type", "string", "format", "duration"))),
			Set.of(), Map.of());

	/** Built-in node (not a validator): calls an async-capable provider and waits for the answer. */
	private static final NodeType EXTERNAL_CALL_BASE = new NodeType("EXTERNAL_CALL", Map.of("type", "object", "required",
			List.of("providerKey", "query", "maxWait"), "properties", Map.of("providerKey", Map.of("type", "string"),
					"query", Map.of("type", "object", "description", "provider parameter -> inputData field"), "expect",
					Map.of("type", "object", "description", "output field -> required value"), "maxWait",
					Map.of("type", "string", "format", "duration"))),
			Set.of("EXTERNAL_PROVIDER"), Map.of());

	private final ValidatorRegistry registry;

	private final Map<String, List<ProviderOperation>> providers;

	private final NodeType externalCall;

	NodeTypeController(ValidatorRegistry registry, ObjectProvider<DataProvider> dataProviders) {
		this.registry = registry;
		this.providers = new TreeMap<>();
		dataProviders.orderedStream().forEach(p -> providers.put(p.key(), p.operations().stream()
				.map(o -> new ProviderOperation(o.key(), new TreeSet<>(o.required()), new TreeSet<>(o.optional()), o.anyParams())).toList()));
		this.externalCall = new NodeType(EXTERNAL_CALL_BASE.key(), EXTERNAL_CALL_BASE.schema(), EXTERNAL_CALL_BASE.capabilities(), providers);
	}

	@GetMapping
	@Operation(summary = "List node types", description = "All validator types registered in this deployment.", security = @SecurityRequirement(name = "apiKey", scopes = "flow:read"))
	List<NodeType> list() {
		return Stream.concat(registry.all().stream().map(this::toDto), Stream.of(HUMAN_APPROVAL, externalCall)).toList();
	}

	@GetMapping("/{key}")
	@Operation(summary = "Get a node type", description = "One node type by key.", security = @SecurityRequirement(name = "apiKey", scopes = "flow:read"))
	@ApiResponse(responseCode = "200", description = "OK")
	@ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound")
	NodeType get(@PathVariable String key) {
		if (HUMAN_APPROVAL.key().equals(key)) {
			return HUMAN_APPROVAL;
		}
		if (externalCall.key().equals(key)) {
			return externalCall;
		}
		return toDto(registry.find(key).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "node type not found: " + key)));
	}

	private NodeType toDto(ValidatorStrategy s) {
		return new NodeType(s.key(), s.schema(), s.capabilities(),
				providers.containsKey(s.key()) ? Map.of(s.key(), providers.get(s.key())) : Map.of());
	}

}
