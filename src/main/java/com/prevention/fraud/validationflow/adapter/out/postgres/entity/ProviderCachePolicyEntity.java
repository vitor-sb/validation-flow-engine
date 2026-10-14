package com.prevention.fraud.validationflow.adapter.out.postgres.entity;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "provider_cache_policy")
public class ProviderCachePolicyEntity {

	@Embeddable
	public record Key(@Column(name = "tenant_id") String tenantId, @Column(name = "provider_key") String providerKey,
			String operation) {
	}

	@EmbeddedId
	public Key id;

	@Column(name = "data_category")
	public String dataCategory;

	@Column(name = "ttl_seconds")
	public Integer ttlSeconds;

	@Column(name = "fallback_on_error_allowed")
	public boolean fallbackOnErrorAllowed;

	public String justification;

	public String owner;

	@Column(name = "review_date")
	public LocalDate reviewDate;

}
