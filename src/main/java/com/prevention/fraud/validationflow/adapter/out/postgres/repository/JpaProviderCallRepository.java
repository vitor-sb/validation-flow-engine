package com.prevention.fraud.validationflow.adapter.out.postgres.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
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
	@Transactional
	public int sweepDead(Instant now) {
		return calls.sweepDead(now);
	}

	@Override
	public List<ProviderCall> listByStatus(String tenantId, ProviderCall.Status status, int page, int size) {
		return calls.findByTenantIdAndStatusOrderByUpdatedAtDesc(tenantId, status.name(), PageRequest.of(page, size))
			.stream().map(ProviderCallMapper::toDomain).toList();
	}

	@Override
	public long countByStatus(String tenantId, ProviderCall.Status status) {
		return calls.countByTenantIdAndStatus(tenantId, status.name());
	}

	@Override
	public Optional<ProviderCall> find(String tenantId, UUID id) {
		return calls.findByTenantIdAndId(tenantId, id).map(ProviderCallMapper::toDomain);
	}

}
