package com.prevention.fraud.validationflow.adapter.out.postgres.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.prevention.fraud.validationflow.adapter.out.postgres.entity.ProviderCallEntity;

public interface ProviderCallJpaRepository extends JpaRepository<ProviderCallEntity, UUID> {

	@Query(value = "SELECT * FROM provider_call WHERE status = 'PENDING' AND mode = 'POLL' AND next_poll_at <= :now "
			+ "ORDER BY next_poll_at LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
	List<ProviderCallEntity> lockDue(@Param("now") Instant now, @Param("limit") int limit);

	Optional<ProviderCallEntity> findByTenantIdAndId(String tenantId, UUID id);

	@Modifying
	@Query(value = "UPDATE provider_call c SET status = 'DEAD', next_poll_at = NULL, updated_at = :now, reason = "
			+ "CASE WHEN e.status = 'TIMED_OUT' THEN 'WAIT_EXPIRED' WHEN e.status = 'CANCELLED' THEN 'EXECUTION_CANCELLED' "
			+ "WHEN c.status = 'FAILED' AND c.reason IS NOT NULL THEN c.reason "
			+ "WHEN c.status = 'FAILED' THEN 'FAILED' ELSE 'EXECUTION_ENDED' END "
			+ "FROM flow_execution e WHERE e.tenant_id = c.tenant_id AND e.id = c.execution_id "
			+ "AND (c.status = 'FAILED' OR (c.status = 'PENDING' AND e.status <> 'WAITING'))", nativeQuery = true)
	int sweepDead(@Param("now") Instant now);

	List<ProviderCallEntity> findByTenantIdAndStatusOrderByUpdatedAtDesc(String tenantId, String status, Pageable page);

	long countByTenantIdAndStatus(String tenantId, String status);

	long countByStatus(String status);

}
