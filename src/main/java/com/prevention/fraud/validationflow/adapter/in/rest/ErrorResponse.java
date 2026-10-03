package com.prevention.fraud.validationflow.adapter.in.rest;

import java.util.List;

public record ErrorResponse(String code, boolean retryable, List<String> details) {
}
