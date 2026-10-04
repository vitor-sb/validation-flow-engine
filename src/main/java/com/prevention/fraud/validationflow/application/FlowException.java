package com.prevention.fraud.validationflow.application;

import java.util.List;

import com.prevention.fraud.validationflow.domain.GraphValidator.GraphError;

/** Business failures of flow operations; mapped to HTTP by the REST adapter. */
public class FlowException extends RuntimeException {

	public enum Kind { NOT_FOUND, CONFLICT, INVALID, INVALID_CONFIGURATION, INVALID_INPUT, EXECUTION_NOT_FOUND }

	private final Kind kind;

	private final List<GraphError> errors;

	private FlowException(Kind kind, String message, List<GraphError> errors) {
		super(message);
		this.kind = kind;
		this.errors = errors;
	}

	public static FlowException notFound() {
		return new FlowException(Kind.NOT_FOUND, "flow not found", List.of());
	}

	public static FlowException executionNotFound() {
		return new FlowException(Kind.EXECUTION_NOT_FOUND, "execution not found", List.of());
	}

	public static FlowException conflict(String message) {
		return new FlowException(Kind.CONFLICT, message, List.of());
	}

	public static FlowException invalidConfiguration(String message) {
		return new FlowException(Kind.INVALID_CONFIGURATION, message, List.of());
	}

	public static FlowException invalidInput(String message) {
		return new FlowException(Kind.INVALID_INPUT, message, List.of());
	}

	public static FlowException invalid(List<GraphError> errors) {
		return new FlowException(Kind.INVALID, "flow graph is invalid", errors);
	}

	public Kind kind() {
		return kind;
	}

	public List<GraphError> errors() {
		return errors;
	}

}
