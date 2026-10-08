package com.prevention.fraud.validationflow.application.execution;

import com.prevention.fraud.validationflow.application.execution.ports.IdempotencyRepository;
import com.prevention.fraud.validationflow.application.flow.FlowException;
import com.prevention.fraud.validationflow.domain.execution.FlowExecution;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

public class IdempotentExecutionService {

	private static final Duration IDEMPOTENCY_WAIT = Duration.ofSeconds(30);

	private final ExecutionService executions;

	private final IdempotencyRepository repository;

	private final Duration lease;

	public IdempotentExecutionService(ExecutionService executions, IdempotencyRepository repository, Duration lease) {
		this.executions = executions;
		this.repository = repository;
		this.lease = lease;
	}

	/** Result of an idempotent start; {@code replayed} when an earlier execution was returned. */
	public record Outcome(FlowExecution execution, boolean replayed) {
	}

	/**
	 * Same as {@link #execute} but deduplicated per (tenant, key): a repeat with the same payload returns the original
	 * execution, a different payload is a 409, and concurrent calls run the flow once (the loser waits for the winner).
	 */
	public Outcome executeIdempotent(String tenantId, String key, String flowKey, String userType,
			String context, Map<String, Object> inputData, String correlationId) {
		String hash = hash(Arrays.asList(flowKey, userType, context, inputData, correlationId));
		long deadline = System.nanoTime() + IDEMPOTENCY_WAIT.toNanos();
		while (true) {
			if (repository.claimIdempotency(tenantId, key, hash, lease)) {
				FlowExecution ex;
				try {
					ex = executions.execute(tenantId, flowKey, userType, context, inputData, correlationId);
				}
				catch (RuntimeException e) {
					repository.releaseIdempotency(tenantId, key);
					throw e;
				}
				repository.completeIdempotency(tenantId, key, ex.id());
				return new Outcome(ex, false);
			}
			var claim = repository.findIdempotency(tenantId, key);
			if (claim.isPresent()) {
				if (!claim.get().requestHash().equals(hash)) {
					throw FlowException.conflict("Idempotency-Key was already used with a different payload");
				}
				if (claim.get().executionId() != null) {
					return new Outcome(executions.get(tenantId, claim.get().executionId()), true);
				}
			}
			if (System.nanoTime() > deadline) {
				throw FlowException.conflict("request with this Idempotency-Key is still in progress");
			}
			try {
				Thread.sleep(50);
			}
			catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				throw FlowException.conflict("interrupted while waiting for the request with this Idempotency-Key");
			}
		}
	}

	/** SHA-256 of a canonical rendering (map keys sorted) so key order in the payload doesn't matter. */
	private static String hash(Object payload) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(canonical(payload).getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String canonical(Object o) {
		if (o instanceof Map<?, ?> m) {
			var sb = new StringBuilder("{");
			new TreeMap<String, Object>(m.entrySet().stream()
					.collect(java.util.stream.Collectors.toMap(e -> String.valueOf(e.getKey()), Map.Entry::getValue)))
					.forEach((k, v) -> sb.append(k.length()).append(':').append(k).append('=').append(canonical(v)).append(','));
			return sb.append('}').toString();
		}
		if (o instanceof Iterable<?> it) {
			var sb = new StringBuilder("[");
			it.forEach(v -> sb.append(canonical(v)).append(','));
			return sb.append(']').toString();
		}
		return o == null ? "null" : o.getClass().getSimpleName() + ":" + o;
	}
}
