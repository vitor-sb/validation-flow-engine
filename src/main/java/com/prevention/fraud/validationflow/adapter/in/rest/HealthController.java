package com.prevention.fraud.validationflow.adapter.in.rest;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/health")
class HealthController {

	@GetMapping
	Map<String, String> health() {
		return Map.of("status", "UP");
	}

}
