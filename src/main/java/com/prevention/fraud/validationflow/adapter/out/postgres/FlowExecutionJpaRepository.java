package com.prevention.fraud.validationflow.adapter.out.postgres;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.prevention.fraud.validationflow.domain.execution.ExecutionStatus;

/** Every query is scoped by tenantId, except {@link #findByStatusAndStartedAtBefore} (recovery). */
interface FlowExecutionJpaRepository extends JpaRepository<FlowExecutionEntity, UUID> {

	Optional<FlowExecutionEntity> findByTenantIdAndId(String tenantId, UUID id);

	long countByTenantId(String tenantId);

	@Query("select e from FlowExecutionEntity e where e.tenantId = :tenantId order by e.startedAt desc, e.id "
			+ "limit :limit offset :offset")
	List<FlowExecutionEntity> page(@Param("tenantId") String tenantId, @Param("limit") int limit,
			@Param("offset") long offset);

	List<FlowExecutionEntity> findByStatusAndStartedAtBefore(ExecutionStatus status, Instant cutoff);

	/** Optimistic lock by hand: matches only the stored lock_version, and bumps it. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update FlowExecutionEntity e set e.status = :status, e.contextData = :context, e.result = :result,
			  e.errorInfo = :error, e.completedAt = :completedAt, e.lockVersion = e.lockVersion + 1
			where e.tenantId = :tenantId and e.id = :id and e.lockVersion = :lockVersion""")
	int update(@Param("tenantId") String tenantId, @Param("id") UUID id, @Param("lockVersion") long lockVersion,
			@Param("status") ExecutionStatus status, @Param("context") Map<String, Object> context,
			@Param("result") Map<String, Object> result, @Param("error") Map<String, Object> error,
			@Param("completedAt") Instant completedAt);

}
