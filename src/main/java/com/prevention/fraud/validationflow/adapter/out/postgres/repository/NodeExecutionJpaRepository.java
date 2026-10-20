package com.prevention.fraud.validationflow.adapter.out.postgres.repository;

import com.prevention.fraud.validationflow.adapter.out.postgres.entity.NodeExecutionEntity;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Every query is scoped by tenantId. */
public interface NodeExecutionJpaRepository extends JpaRepository<NodeExecutionEntity, UUID> {

	List<NodeExecutionEntity> findByTenantIdAndExecutionIdOrderByStartedAtAscCompletedAtAscAttemptAsc(String tenantId,
			UUID executionId);

}
