package com.prevention.fraud.validationflow.config.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

@Configuration
@EnableConfigurationProperties(ApiKeyProperties.class)
class SecurityConfig {

	// Health is public; everything else needs a valid API key (401) with the right scope (403).
	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, ApiKeyProperties properties,
			@org.springframework.beans.factory.annotation.Value("${app.limits.max-body-bytes:1048576}") long maxBodyBytes) throws Exception {
		return http
				.addFilterBefore(new BodySizeLimitFilter(maxBodyBytes), org.springframework.security.web.session.DisableEncodeUrlFilter.class)
				.csrf(csrf -> csrf.disable())
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.addFilterBefore(new ApiKeyAuthenticationFilter(properties), AnonymousAuthenticationFilter.class)
				.exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/actuator/health/**", "/api/v1/health").permitAll()
						// authenticated by the call's own token (see ProviderCallbackService), not by an API key
						.requestMatchers(HttpMethod.POST, "/api/v1/provider-callbacks/*").permitAll()
						.requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
						.requestMatchers(HttpMethod.PATCH, "/api/v1/flows/*/activate").hasAuthority("SCOPE_flow:activate")
						.requestMatchers(HttpMethod.PATCH, "/api/v1/flows/*/archive").hasAuthority("SCOPE_flow:activate")
						.requestMatchers(HttpMethod.POST, "/api/v1/flows", "/api/v1/flows/validate")
								.hasAuthority("SCOPE_flow:write")
						.requestMatchers(HttpMethod.PUT, "/api/v1/flows/*").hasAuthority("SCOPE_flow:write")
						.requestMatchers(HttpMethod.GET, "/api/v1/flows/**", "/api/v1/node-types/**")
								.hasAuthority("SCOPE_flow:read")
						.requestMatchers(HttpMethod.POST, "/api/v1/executions").hasAuthority("SCOPE_validation:execute")
						.requestMatchers(HttpMethod.POST, "/api/v1/executions/*/resume").hasAuthority("SCOPE_validation:resume")
						.requestMatchers(HttpMethod.POST, "/api/v1/executions/*/cancel").hasAuthority("SCOPE_validation:cancel")
						.requestMatchers(HttpMethod.GET, "/api/v1/executions/**").hasAuthority("SCOPE_validation:read")
						.requestMatchers(HttpMethod.GET, "/api/v1/providers").hasAuthority("SCOPE_provider:read")
						.requestMatchers(HttpMethod.PUT, "/api/v1/providers/*").hasAuthority("SCOPE_provider:admin")
						.requestMatchers("/api/v1/provider-calls/**").hasAuthority("SCOPE_provider:admin")
						.anyRequest().authenticated())
				.build();
	}

}
