package com.prevention.fraud.validationflow.adapter.in.rest;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Error body used by every non-2xx response.")
public record ErrorResponse(@Schema(description = "Stable machine-readable error code", example = "FLOW_NOT_FOUND") String code,
		@Schema(description = "True when retrying the same request may succeed") boolean retryable,
		@Schema(description = "Human-readable details", example = "[\"flow not found\"]") List<String> details) {
}
