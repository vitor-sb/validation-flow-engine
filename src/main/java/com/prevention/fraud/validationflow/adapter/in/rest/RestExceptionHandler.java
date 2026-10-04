package com.prevention.fraud.validationflow.adapter.in.rest;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.prevention.fraud.validationflow.application.FlowException;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class RestExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(RestExceptionHandler.class);

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		return ResponseEntity.badRequest().body(new ErrorResponse("VALIDATION_ERROR", false, e.getBindingResult()
				.getFieldErrors().stream().map(f -> f.getField() + ": " + f.getDefaultMessage()).toList()));
	}

	@Override
	protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		return ResponseEntity.badRequest()
				.body(new ErrorResponse("MALFORMED_REQUEST", false, List.of("request body is not valid JSON")));
	}

	// Every other framework exception (type mismatch, 405, 404 route, missing param...) ends up here:
	// generic ErrorResponse, never Spring's default body.
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		String code = switch (status.value()) {
			case 404 -> "NOT_FOUND";
			case 405 -> "METHOD_NOT_ALLOWED";
			case 406, 415 -> "UNSUPPORTED_MEDIA_TYPE";
			case 400 -> "BAD_REQUEST";
			default -> status.is5xxServerError() ? "INTERNAL_ERROR" : "REQUEST_ERROR";
		};
		String detail = e instanceof ErrorResponseException ere && ere.getBody().getDetail() != null
				? ere.getBody().getDetail() : "request could not be processed";
		return ResponseEntity.status(status).headers(headers).body(new ErrorResponse(code, false, List.of(detail)));
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ErrorResponse> unexpected(Exception e) {
		log.error("unexpected error", e);
		return ResponseEntity.internalServerError()
				.body(new ErrorResponse("INTERNAL_ERROR", true, List.of("unexpected error")));
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
			case EXECUTION_NOT_FOUND -> org.springframework.http.ResponseEntity.status(HttpStatus.NOT_FOUND)
					.body(new ErrorResponse("EXECUTION_NOT_FOUND", false, java.util.List.of(e.getMessage())));
			case CONFLICT -> org.springframework.http.ResponseEntity.status(HttpStatus.CONFLICT)
					.body(new ErrorResponse("CONFLICT", false, java.util.List.of(e.getMessage())));
			case INVALID_CONFIGURATION -> org.springframework.http.ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(new ErrorResponse("INVALID_FLOW_CONFIGURATION", false, java.util.List.of(e.getMessage())));
			case INVALID_INPUT -> org.springframework.http.ResponseEntity.badRequest()
					.body(new ErrorResponse("INVALID_INPUT", false, java.util.List.of(e.getMessage())));
			case INVALID -> org.springframework.http.ResponseEntity.unprocessableContent()
					.body(new ErrorResponse("INVALID_FLOW", false,
							e.errors().stream().map(g -> g.code() + ": " + g.message()).toList()));
		};
	}

}
