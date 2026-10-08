package com.prevention.fraud.validationflow.adapter.out.postgres;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Read-only view for lookups; every write goes through the native queries in {@link IdempotencyKeyJpaRepository}. */
@Entity
@Table(name = "idempotency_key")
class IdempotencyKeyEntity {

	@Embeddable
	record Key(@Column(name = "tenant_id") String tenantId, @Column(name = "idempotency_key") String idempotencyKey) {
	}

	@EmbeddedId
	Key id;

	@Column(name = "request_hash")
	String requestHash;

	@Column(name = "execution_id")
	UUID executionId;

}
