package com.prevention.fraud.validationflow.adapter.out.postgres;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface ExecutionAuditLogJpaRepository extends JpaRepository<ExecutionAuditLogEntity, UUID> {
}
