package com.prevention.fraud.validationflow.adapter.out.postgres.repository;

import com.prevention.fraud.validationflow.adapter.out.postgres.entity.IdempotencyKeyEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.prevention.fraud.validationflow.application.execution.ports.IdempotencyRepository;

@Repository
public class JpaIdempotencyRepository implements IdempotencyRepository {

	private final IdempotencyKeyJpaRepository keys;

	JpaIdempotencyRepository(IdempotencyKeyJpaRepository keys) {
		this.keys = keys;
	}

	@Override
	@Transactional
	public boolean claimIdempotency(String tenantId, String key, String requestHash, Duration lease) {
		return keys.claim(tenantId, key, requestHash, lease.toMillis() / 1000.0) == 1;
	}

	@Override
	public Optional<IdempotencyClaim> findIdempotency(String tenantId, String key) {
		return keys.findById(new IdempotencyKeyEntity.Key(tenantId, key))
				.map(k -> new IdempotencyClaim(k.requestHash, k.executionId));
	}

	@Override
	@Transactional
	public void completeIdempotency(String tenantId, String key, UUID executionId) {
		keys.complete(tenantId, key, executionId);
	}

	@Override
	@Transactional
	public void releaseIdempotency(String tenantId, String key) {
		keys.release(tenantId, key);
	}

	@Override
	@Transactional
	public int deleteExpiredOrphanIdempotency() {
		return keys.deleteExpiredOrphan();
	}

	@Override
	@Transactional
	public int deleteIdempotencyCreatedBefore(Instant cutoff) {
		return keys.deleteCreatedBefore(cutoff);
	}

}
