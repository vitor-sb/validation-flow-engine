package com.prevention.fraud.validationflow.adapter.out.postgres.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.prevention.fraud.validationflow.adapter.out.postgres.entity.ProviderConfigAuditEntity;

public interface ProviderConfigAuditJpaRepository extends JpaRepository<ProviderConfigAuditEntity, UUID> {
}
