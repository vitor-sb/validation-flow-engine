package com.prevention.fraud.validationflow.adapter.out.postgres.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.prevention.fraud.validationflow.adapter.out.postgres.entity.ProviderCachePolicyEntity;

public interface ProviderCachePolicyJpaRepository
		extends JpaRepository<ProviderCachePolicyEntity, ProviderCachePolicyEntity.Key> {

	List<ProviderCachePolicyEntity> findByIdTenantIdAndIdProviderKey(String tenantId, String providerKey);

}
