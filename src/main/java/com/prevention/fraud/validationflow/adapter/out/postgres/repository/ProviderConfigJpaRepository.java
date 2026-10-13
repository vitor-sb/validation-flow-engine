package com.prevention.fraud.validationflow.adapter.out.postgres.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.prevention.fraud.validationflow.adapter.out.postgres.entity.ProviderConfigEntity;

public interface ProviderConfigJpaRepository extends JpaRepository<ProviderConfigEntity, ProviderConfigEntity.Key> {

	List<ProviderConfigEntity> findByIdTenantId(String tenantId);

}
