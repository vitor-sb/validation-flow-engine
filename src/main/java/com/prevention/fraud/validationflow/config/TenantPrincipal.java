package com.prevention.fraud.validationflow.config;

/** Authenticated caller; the tenant always comes from the credential, never from the request body. */
public record TenantPrincipal(String tenantId) {
}
