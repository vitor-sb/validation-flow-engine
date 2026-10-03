package com.prevention.fraud.validationflow.adapter.in.rest;

import com.prevention.fraud.validationflow.application.FlowException;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class RestExceptionHandler {

	@ExceptionHandler(MethodArgumentNotValidException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	ErrorResponse invalid(MethodArgumentNotValidException e) {
		return new ErrorResponse("VALIDATION_ERROR", false, e.getBindingResult().getFieldErrors().stream()
				.map(f -> f.getField() + ": " + f.getDefaultMessage()).toList());
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	ErrorResponse unreadable(HttpMessageNotReadableException e) {
		return new ErrorResponse("MALFORMED_REQUEST", false, java.util.List.of("request body is not valid JSON"));
	}

	@ExceptionHandler(DuplicateKeyException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	ErrorResponse duplicate(DuplicateKeyException e) {
		return new ErrorResponse("CONFLICT", true, java.util.List.of("resource already exists"));
	}

	@ExceptionHandler(FlowException.class)
	org.springframework.http.ResponseEntity<ErrorResponse> flow(FlowException e) {
		return switch (e.kind()) {
			case NOT_FOUND -> org.springframework.http.ResponseEntity.status(HttpStatus.NOT_FOUND)
					.body(new ErrorResponse("FLOW_NOT_FOUND", false, java.util.List.of(e.getMessage())));
			case CONFLICT -> org.springframework.http.ResponseEntity.status(HttpStatus.CONFLICT)
					.body(new ErrorResponse("CONFLICT", false, java.util.List.of(e.getMessage())));
			case INVALID_CONFIGURATION -> org.springframework.http.ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(new ErrorResponse("INVALID_FLOW_CONFIGURATION", false, java.util.List.of(e.getMessage())));
			case INVALID -> org.springframework.http.ResponseEntity.unprocessableContent()
					.body(new ErrorResponse("INVALID_FLOW", false,
							e.errors().stream().map(g -> g.code() + ": " + g.message()).toList()));
		};
	}

}
