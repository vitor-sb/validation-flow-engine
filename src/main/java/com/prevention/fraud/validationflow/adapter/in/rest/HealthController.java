package com.prevention.fraud.validationflow.adapter.in.rest;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/health")
@Tag(name = "Health")
class HealthController {

	@GetMapping
	@Operation(summary = "Liveness check", description = "Public endpoint (no API key). Returns 200 while the service is up.")
	Map<String, String> health() {
		return Map.of("status", "UP");
	}

}
