package com.prevention.fraud.validationflow.adapter.out.postgres.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "provider_call")
public class ProviderCallEntity {

	@Id
	public UUID id;

	@Column(name = "tenant_id")
	public String tenantId;

	@Column(name = "execution_id")
	public UUID executionId;

	@Column(name = "node_id")
	public String nodeId;

	@Column(name = "provider_key")
	public String providerKey;

	@Column(name = "external_ref")
	public String externalRef;

	public String mode;

	public String status;

	public int attempts;

	@Column(name = "next_poll_at")
	public Instant nextPollAt;

	public Instant deadline;

	@Column(name = "callback_token_hash")
	public String callbackTokenHash;

	@Column(name = "created_at")
	public Instant createdAt;

	@Column(name = "updated_at")
	public Instant updatedAt;

	public String reason;

	public String operation;

}
