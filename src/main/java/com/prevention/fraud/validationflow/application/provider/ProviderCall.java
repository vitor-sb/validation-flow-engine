package com.prevention.fraud.validationflow.application.provider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/** A provider call waiting for an answer. Only the hash of the callback token is kept. */
public record ProviderCall(UUID id, String tenantId, UUID executionId, String nodeId, String providerKey,
		String externalRef, ProviderResult.Mode mode, Status status, int attempts, Instant nextPollAt, Instant deadline,
		String callbackTokenHash, Instant createdAt, Instant updatedAt) {

	public enum Status {
		PENDING, COMPLETED, FAILED, DEAD
	}

	private static final SecureRandom RANDOM = new SecureRandom();

	public ProviderCall withState(Status status, int attempts, Instant nextPollAt, Instant now) {
		return new ProviderCall(id, tenantId, executionId, nodeId, providerKey, externalRef, mode, status, attempts,
				nextPollAt, deadline, callbackTokenHash, createdAt, now);
	}

	/** A new random token; the caller hands it to the provider and must not persist it. */
	public static String newToken() {
		var b = new byte[32];
		RANDOM.nextBytes(b);
		return HexFormat.of().formatHex(b);
	}

	public static String hashToken(String token) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

}
