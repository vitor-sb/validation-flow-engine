package com.prevention.fraud.validationflow.adapter.out.postgres.entity;


import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Read-only view for lookups; every write goes through the native queries in {@code IdempotencyKeyJpaRepository}. */
@Entity
@Table(name = "idempotency_key")
public class IdempotencyKeyEntity {

	@Embeddable
	public record Key(@Column(name = "tenant_id") String tenantId, @Column(name = "idempotency_key") String idempotencyKey) {
	}

	@EmbeddedId
	public Key id;

	@Column(name = "request_hash")
	public String requestHash;

	@Column(name = "execution_id")
	public UUID executionId;

}
