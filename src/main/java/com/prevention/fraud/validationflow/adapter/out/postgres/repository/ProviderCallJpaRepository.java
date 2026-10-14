package com.prevention.fraud.validationflow.adapter.out.postgres.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.prevention.fraud.validationflow.adapter.out.postgres.entity.ProviderCallEntity;

public interface ProviderCallJpaRepository extends JpaRepository<ProviderCallEntity, UUID> {

	@Query(value = "SELECT * FROM provider_call WHERE status = 'PENDING' AND mode = 'POLL' AND next_poll_at <= :now "
			+ "ORDER BY next_poll_at LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
	List<ProviderCallEntity> lockDue(@Param("now") Instant now, @Param("limit") int limit);

	Optional<ProviderCallEntity> findByTenantIdAndId(String tenantId, UUID id);

}
