package com.prevention.fraud.validationflow.config;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Resolves {@code X-API-Key} into a {@link TenantPrincipal} with one {@code SCOPE_*} authority per scope. */
class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

	static final String HEADER = "X-API-Key";

	private final ApiKeyProperties properties;

	ApiKeyAuthenticationFilter(ApiKeyProperties properties) {
		this.properties = properties;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String key = request.getHeader(HEADER);
		if (key != null) {
			// ponytail: linear scan over configured keys, index by key hash if the list grows.
			properties.apiKeys().stream().filter(k -> key.equals(k.key())).findFirst().ifPresent(k -> {
				List<SimpleGrantedAuthority> scopes = k.scopes() == null ? List.of()
						: k.scopes().stream().map(s -> new SimpleGrantedAuthority("SCOPE_" + s)).toList();
				SecurityContextHolder.getContext().setAuthentication(
						UsernamePasswordAuthenticationToken.authenticated(new TenantPrincipal(k.tenantId()), null, scopes));
			});
		}
		chain.doFilter(request, response);
	}

}
