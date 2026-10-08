package com.prevention.fraud.validationflow.adapter.out.postgres;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Per-key queries are scoped by tenantId; the two delete* sweeps are cross-tenant by design (recovery, retention). */
interface IdempotencyKeyJpaRepository extends JpaRepository<IdempotencyKeyEntity, IdempotencyKeyEntity.Key> {

	/** An orphaned claim (no execution_id, lease expired) is taken over atomically; the row lock gives one winner. */
	@Modifying(clearAutomatically = true)
	@Query(nativeQuery = true, value = """
			INSERT INTO idempotency_key (tenant_id, idempotency_key, request_hash, locked_until)
			VALUES (:tenantId, :key, :requestHash, now() + make_interval(secs => :leaseSeconds))
			ON CONFLICT (tenant_id, idempotency_key) DO UPDATE SET request_hash = EXCLUDED.request_hash,
			  locked_until = EXCLUDED.locked_until, created_at = now()
			WHERE idempotency_key.execution_id IS NULL AND idempotency_key.locked_until < now()""")
	int claim(@Param("tenantId") String tenantId, @Param("key") String key, @Param("requestHash") String requestHash,
			@Param("leaseSeconds") double leaseSeconds);

	@Modifying(clearAutomatically = true)
	@Query("update IdempotencyKeyEntity k set k.executionId = :executionId "
			+ "where k.id.tenantId = :tenantId and k.id.idempotencyKey = :key")
	int complete(@Param("tenantId") String tenantId, @Param("key") String key,
			@Param("executionId") UUID executionId);

	@Modifying(clearAutomatically = true)
	@Query("delete from IdempotencyKeyEntity k "
			+ "where k.id.tenantId = :tenantId and k.id.idempotencyKey = :key and k.executionId is null")
	int release(@Param("tenantId") String tenantId, @Param("key") String key);

	@Modifying(clearAutomatically = true)
	@Query(nativeQuery = true,
			value = "DELETE FROM idempotency_key WHERE execution_id IS NULL AND locked_until < now()")
	int deleteExpiredOrphan();

	@Modifying(clearAutomatically = true)
	@Query(nativeQuery = true, value = """
			DELETE FROM idempotency_key WHERE created_at < :cutoff
			AND NOT (execution_id IS NULL AND locked_until > now())""")
	int deleteCreatedBefore(@Param("cutoff") Instant cutoff);

}
