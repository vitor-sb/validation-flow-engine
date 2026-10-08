package com.prevention.fraud.validationflow.adapter.out.postgres;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.prevention.fraud.validationflow.application.execution.ports.IdempotencyRepository;


@Repository
class JdbcIdempotencyRepository implements IdempotencyRepository {

	private final JdbcTemplate jdbc;

	JdbcIdempotencyRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public boolean claimIdempotency(String tenantId, String key, String requestHash, java.time.Duration lease) {
		// an orphaned claim (no execution_id, lease expired) is taken over atomically; the row lock gives one winner
		return jdbc.update("INSERT INTO idempotency_key (tenant_id, idempotency_key, request_hash, locked_until) "
				+ "VALUES (?, ?, ?, now() + make_interval(secs => ?)) "
				+ "ON CONFLICT (tenant_id, idempotency_key) DO UPDATE SET request_hash = EXCLUDED.request_hash, "
				+ "locked_until = EXCLUDED.locked_until, created_at = now() "
				+ "WHERE idempotency_key.execution_id IS NULL AND idempotency_key.locked_until < now()",
				tenantId, key, requestHash, lease.toMillis() / 1000.0) == 1;
	}

	@Override
	public Optional<IdempotencyClaim> findIdempotency(String tenantId, String key) {
		return jdbc.query("SELECT request_hash, execution_id FROM idempotency_key WHERE tenant_id = ? AND idempotency_key = ?",
				(rs, n) -> new IdempotencyClaim(rs.getString(1), rs.getObject(2, UUID.class)), tenantId, key).stream()
				.findFirst();
	}

	@Override
	public void completeIdempotency(String tenantId, String key, UUID executionId) {
		jdbc.update("UPDATE idempotency_key SET execution_id = ? WHERE tenant_id = ? AND idempotency_key = ?",
				executionId, tenantId, key);
	}

	@Override
	public void releaseIdempotency(String tenantId, String key) {
		jdbc.update("DELETE FROM idempotency_key WHERE tenant_id = ? AND idempotency_key = ? AND execution_id IS NULL",
				tenantId, key);
	}

	@Override
	public int deleteExpiredOrphanIdempotency() {
		return jdbc.update("DELETE FROM idempotency_key WHERE execution_id IS NULL AND locked_until < now()");
	}

	@Override
	public int deleteIdempotencyCreatedBefore(java.time.Instant cutoff) {
		return jdbc.update("DELETE FROM idempotency_key WHERE created_at < ? "
				+ "AND NOT (execution_id IS NULL AND locked_until > now())", java.sql.Timestamp.from(cutoff));
	}

}
