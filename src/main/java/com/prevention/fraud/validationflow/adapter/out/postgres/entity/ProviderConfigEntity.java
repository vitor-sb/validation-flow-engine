package com.prevention.fraud.validationflow.adapter.out.postgres.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "provider_config")
public class ProviderConfigEntity {

	@Embeddable
	public record Key(@Column(name = "tenant_id") String tenantId, @Column(name = "provider_key") String providerKey) {
	}

	@EmbeddedId
	public Key id;

	public boolean enabled;

	@Column(name = "base_url")
	public String baseUrl;

	@Column(name = "connect_timeout_ms")
	public Integer connectTimeoutMs;

	@Column(name = "read_timeout_ms")
	public Integer readTimeoutMs;

	@Column(name = "credentials_ref")
	public String credentialsRef;

	@Column(name = "callback_secret_ref")
	public String callbackSecretRef;

	@Column(name = "updated_by")
	public String updatedBy;

	@Column(name = "updated_at")
	public Instant updatedAt;

}
