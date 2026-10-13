package com.prevention.fraud.validationflow.adapter.out.postgres.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.prevention.fraud.validationflow.adapter.out.postgres.entity.ProviderConfigEntity;
import com.prevention.fraud.validationflow.adapter.out.postgres.mapper.ProviderConfigMapper;
import com.prevention.fraud.validationflow.application.provider.ProviderConfig;
import com.prevention.fraud.validationflow.application.provider.ProviderConfigRepository;

@Repository
public class JpaProviderConfigRepository implements ProviderConfigRepository {

	private final ProviderConfigJpaRepository configs;

	JpaProviderConfigRepository(ProviderConfigJpaRepository configs) {
		this.configs = configs;
	}

	@Override
	public Optional<ProviderConfig> find(String tenantId, String providerKey) {
		return configs.findById(new ProviderConfigEntity.Key(tenantId, providerKey)).map(ProviderConfigMapper::toDomain);
	}

	@Override
	public List<ProviderConfig> findAll(String tenantId) {
		return configs.findByIdTenantId(tenantId).stream().map(ProviderConfigMapper::toDomain).toList();
	}

	@Override
	@Transactional
	public void save(ProviderConfig config) {
		configs.save(ProviderConfigMapper.toEntity(config));
	}

}
