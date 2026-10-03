package com.prevention.fraud.validationflow.adapter.in.rest;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.prevention.fraud.validationflow.application.ValidatorRegistry;
import com.prevention.fraud.validationflow.application.ValidatorStrategy;

@RestController
@RequestMapping("/api/v1/node-types")
class NodeTypeController {

	record NodeType(String key, Map<String, Object> schema, Set<String> capabilities) {
	}

	private final ValidatorRegistry registry;

	NodeTypeController(ValidatorRegistry registry) {
		this.registry = registry;
	}

	@GetMapping
	List<NodeType> list() {
		return registry.all().stream().map(NodeTypeController::toDto).toList();
	}

	@GetMapping("/{key}")
	NodeType get(@PathVariable String key) {
		return toDto(registry.find(key).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "node type not found: " + key)));
	}

	private static NodeType toDto(ValidatorStrategy s) {
		return new NodeType(s.key(), s.schema(), s.capabilities());
	}

}
