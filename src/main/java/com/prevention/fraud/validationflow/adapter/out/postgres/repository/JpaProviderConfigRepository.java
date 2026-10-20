package com.prevention.fraud.validationflow.adapter.out.postgres.repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.prevention.fraud.validationflow.adapter.out.postgres.entity.ProviderConfigAuditEntity;
import com.prevention.fraud.validationflow.adapter.out.postgres.entity.ProviderConfigEntity;
import com.prevention.fraud.validationflow.adapter.out.postgres.mapper.ProviderConfigMapper;
import com.prevention.fraud.validationflow.application.provider.ProviderConfig;
import com.prevention.fraud.validationflow.application.provider.ProviderConfigRepository;

@Repository
public class JpaProviderConfigRepository implements ProviderConfigRepository {

	private final ProviderConfigJpaRepository configs;

	private final ProviderConfigAuditJpaRepository audit;

	JpaProviderConfigRepository(ProviderConfigJpaRepository configs, ProviderConfigAuditJpaRepository audit) {
		this.configs = configs;
		this.audit = audit;
	}

	@Override
	public Optional<ProviderConfig> find(String tenantId, String providerKey) {
		return configs.findById(new ProviderConfigEntity.Key(tenantId, providerKey)).map(ProviderConfigMapper::toDomain);
	}

	@Override
	public List<ProviderConfig> findAll(String tenantId) {
		return configs.findByIdTenantId(tenantId).stream().map(ProviderConfigMapper::toDomain).toList();
	}

	/** Writes the row and its audit entry in one transaction. Details hold the reference to the secret, never a secret. */
	@Override
	@Transactional
	public void save(ProviderConfig config) {
		boolean created = !configs.existsById(new ProviderConfigEntity.Key(config.tenantId(), config.providerKey()));
		configs.save(ProviderConfigMapper.toEntity(config));
		var a = new ProviderConfigAuditEntity();
		a.id = UUID.randomUUID();
		a.tenantId = config.tenantId();
		a.providerKey = config.providerKey();
		a.actor = config.updatedBy();
		a.eventType = created ? "PROVIDER_CONFIG_CREATED" : "PROVIDER_CONFIG_UPDATED";
		var d = new LinkedHashMap<String, Object>();
		d.put("enabled", config.enabled());
		d.put("baseUrl", config.baseUrl());
		d.put("connectTimeoutMs", config.connectTimeoutMs());
		d.put("readTimeoutMs", config.readTimeoutMs());
		d.put("credentialsRef", config.credentialsRef());
		a.details = d;
		audit.save(a);
	}

}
