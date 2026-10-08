package com.prevention.fraud.validationflow.config.security;

/** Authenticated caller; the tenant always comes from the credential, never from the request body. */
public record TenantPrincipal(String tenantId) {
}
