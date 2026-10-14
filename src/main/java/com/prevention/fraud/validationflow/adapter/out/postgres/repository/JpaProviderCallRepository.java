package com.prevention.fraud.validationflow.adapter.out.postgres.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

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
	public Optional<ProviderCall> find(String tenantId, UUID id) {
		return calls.findByTenantIdAndId(tenantId, id).map(ProviderCallMapper::toDomain);
	}

}
