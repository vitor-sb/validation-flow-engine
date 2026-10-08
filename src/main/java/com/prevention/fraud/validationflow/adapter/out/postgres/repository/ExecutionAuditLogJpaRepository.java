package com.prevention.fraud.validationflow.adapter.out.postgres.repository;

import com.prevention.fraud.validationflow.adapter.out.postgres.entity.ExecutionAuditLogEntity;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionAuditLogJpaRepository extends JpaRepository<ExecutionAuditLogEntity, UUID> {
}
