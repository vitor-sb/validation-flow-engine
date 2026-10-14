package com.prevention.fraud.validationflow.adapter.out.postgres.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.prevention.fraud.validationflow.adapter.out.postgres.mapper.ProviderCallMapper;
import com.prevention.fraud.validationflow.application.provider.ProviderCall;
import com.prevention.fraud.validationflow.application.provider.ProviderCallRepository;

@Repository
public class JpaProviderCallRepository implements ProviderCallRepository {

	private final ProviderCallJpaRepository calls;

	JpaProviderCallRepository(ProviderCallJpaRepository calls) {
		this.calls = calls;
	}

	@Override
	public void save(ProviderCall call) {
		calls.save(ProviderCallMapper.toEntity(call));
	}

	@Override
	public Optional<ProviderCall> findById(UUID id) {
		return calls.findById(id).map(ProviderCallMapper::toDomain);
	}

	@Override
	@Transactional
	public List<ProviderCall> claimDuePolls(Instant now, Instant leaseUntil, int limit) {
		var rows = calls.lockDue(now, limit);
		rows.forEach(r -> r.nextPollAt = leaseUntil);
		return rows.stream().map(ProviderCallMapper::toDomain).toList();
	}

	@Override
	public Optional<ProviderCall> find(String tenantId, UUID id) {
		return calls.findByTenantIdAndId(tenantId, id).map(ProviderCallMapper::toDomain);
	}

}
