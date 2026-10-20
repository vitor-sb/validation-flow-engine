package com.prevention.fraud.validationflow.adapter.in.rest;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.prevention.fraud.validationflow.application.flow.FlowException;
import com.prevention.fraud.validationflow.application.execution.ProviderCallbackService;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/provider-callbacks")
@Tag(name = "Provider Callbacks")
class ProviderCallbackController {

	record CallbackResponse(@io.swagger.v3.oas.annotations.media.Schema(allowableValues = { "ACCEPTED", "DUPLICATE" }) String status) {
	}

	private final ProviderCallbackService service;

	private final JsonMapper json = JsonMapper.builder().build();

	ProviderCallbackController(ProviderCallbackService service) {
		this.service = service;
	}

	@PostMapping("/{callId}")
	@Operation(summary = "Deliver the result of a pending provider call", description = "No API key: authenticated by the call's token in `X-Callback-Token`. When the provider config has a `callbackSecretRef`, `X-Signature` must carry the hex HMAC-SHA256 of the raw body. A repeated callback returns 200 (`DUPLICATE`) without effect; a DEAD, FAILED or expired call is 409; a wrong token or signature is 401 whether or not the call exists.", security = {})
	@ApiResponse(responseCode = "401", description = "Invalid token or signature")
	@ApiResponse(responseCode = "409", ref = "#/components/responses/Conflict")
	CallbackResponse callback(@PathVariable UUID callId, @RequestHeader(name = "X-Callback-Token", required = false) String token,
			@RequestHeader(name = "X-Signature", required = false) String signature, @RequestBody byte[] raw) {
		Map<String, Object> body;
		try {
			body = json.readValue(raw, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
			});
		}
		catch (JacksonException e) {
			// authentication comes first, so an unauthenticated caller learns nothing from a 400
			body = null;
		}
		var outcome = service.accept(callId, token, signature, raw, body);
		if (outcome == ProviderCallbackService.Outcome.UNAUTHORIZED) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid callback credentials");
		}
		return new CallbackResponse(outcome.name());
	}

}
