package com.prevention.fraud.validationflow.adapter.out.postgres.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.prevention.fraud.validationflow.adapter.out.postgres.entity.ProviderCacheEntryEntity;

public interface ProviderCacheEntryJpaRepository extends JpaRepository<ProviderCacheEntryEntity, UUID> {

	@Modifying(clearAutomatically = true)
	@Query("delete from ProviderCacheEntryEntity e where e.validUntil < :cutoff")
	int deleteExpiredBefore(@Param("cutoff") Instant cutoff);

	Optional<ProviderCacheEntryEntity> findByTenantIdAndProviderKeyAndOperationAndRequestKey(String tenantId,
			String providerKey, String operation, String requestKey);

}
