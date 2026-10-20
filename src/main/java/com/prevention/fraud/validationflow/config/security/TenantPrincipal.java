package com.prevention.fraud.validationflow.config.security;

/** Authenticated caller; the tenant always comes from the credential, never from the request body. {@code subject} names the API key for audit. */
public record TenantPrincipal(String tenantId, String subject) {
}
