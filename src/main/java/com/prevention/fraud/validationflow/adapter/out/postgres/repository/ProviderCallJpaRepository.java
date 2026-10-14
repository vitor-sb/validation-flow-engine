package com.prevention.fraud.validationflow.adapter.out.postgres.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.prevention.fraud.validationflow.adapter.out.postgres.entity.ProviderCallEntity;

public interface ProviderCallJpaRepository extends JpaRepository<ProviderCallEntity, UUID> {

	Optional<ProviderCallEntity> findByTenantIdAndId(String tenantId, UUID id);

}
