package com.prevention.fraud.validationflow.adapter.out.postgres;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Every query is scoped by tenantId. */
interface NodeExecutionJpaRepository extends JpaRepository<NodeExecutionEntity, UUID> {

	List<NodeExecutionEntity> findByTenantIdAndExecutionIdOrderByStartedAtAscCompletedAtAscAttemptAsc(String tenantId,
			UUID executionId);

}
