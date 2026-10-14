package com.prevention.fraud.validationflow.adapter.out.postgres.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.prevention.fraud.validationflow.adapter.out.postgres.entity.ProviderCacheEntryEntity;

public interface ProviderCacheEntryJpaRepository extends JpaRepository<ProviderCacheEntryEntity, UUID> {

	Optional<ProviderCacheEntryEntity> findByTenantIdAndProviderKeyAndOperationAndRequestKey(String tenantId,
			String providerKey, String operation, String requestKey);

}
