package com.prevention.fraud.validationflow.adapter.out.postgres.mapper;

import com.prevention.fraud.validationflow.adapter.out.postgres.entity.ProviderCallEntity;
import com.prevention.fraud.validationflow.application.provider.ProviderCall;
import com.prevention.fraud.validationflow.application.provider.ProviderResult;

public final class ProviderCallMapper {

	private ProviderCallMapper() {
	}

	public static ProviderCall toDomain(ProviderCallEntity e) {
		return new ProviderCall(e.id, e.tenantId, e.executionId, e.nodeId, e.providerKey, e.externalRef,
				ProviderResult.Mode.valueOf(e.mode), ProviderCall.Status.valueOf(e.status), e.attempts, e.nextPollAt,
				e.deadline, e.callbackTokenHash, e.createdAt, e.updatedAt);
	}

	public static ProviderCallEntity toEntity(ProviderCall c) {
		var e = new ProviderCallEntity();
		e.id = c.id();
		e.tenantId = c.tenantId();
		e.executionId = c.executionId();
		e.nodeId = c.nodeId();
		e.providerKey = c.providerKey();
		e.externalRef = c.externalRef();
		e.mode = c.mode().name();
		e.status = c.status().name();
		e.attempts = c.attempts();
		e.nextPollAt = c.nextPollAt();
		e.deadline = c.deadline();
		e.callbackTokenHash = c.callbackTokenHash();
		e.createdAt = c.createdAt();
		e.updatedAt = c.updatedAt();
		return e;
	}

}
