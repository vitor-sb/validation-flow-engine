package com.prevention.fraud.validationflow.adapter.out.postgres.entity;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "provider_cache_entry")
public class ProviderCacheEntryEntity {

	@Id
	public UUID id;

	@Column(name = "tenant_id")
	public String tenantId;

	@Column(name = "provider_key")
	public String providerKey;

	public String operation;

	@Column(name = "request_key")
	public String requestKey;

	@JdbcTypeCode(SqlTypes.JSON)
	public Map<String, Object> response;

	@Column(name = "data_category")
	public String dataCategory;

	@Column(name = "fetched_at")
	public Instant fetchedAt;

	@Column(name = "valid_until")
	public Instant validUntil;

	@Column(name = "policy_applied")
	public String policyApplied;

}
