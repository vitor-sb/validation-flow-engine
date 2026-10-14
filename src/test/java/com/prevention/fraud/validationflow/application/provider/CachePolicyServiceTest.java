package com.prevention.fraud.validationflow.application.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class CachePolicyServiceTest {

	private final ProviderCacheRepository repo = mock(ProviderCacheRepository.class);

	private final CachePolicyService service = new CachePolicyService(repo,
			Map.of("LOW_VOLATILITY", Duration.ofHours(24), "MEDIUM_VOLATILITY", Duration.ofHours(1),
					"HIGH_VOLATILITY", Duration.ofMinutes(5)),
			Duration.ofHours(48));

	private static ProviderCachePolicy policy(String category, Integer ttl, String why, String owner, LocalDate review) {
		return new ProviderCachePolicy("t", "p", "op", category, ttl, false, why, owner, review);
	}

	private Optional<ProviderCachePolicy> resolve(ProviderCachePolicy p) {
		when(repo.findPolicy("t", "p", "op")).thenReturn(Optional.ofNullable(p));
		return service.resolve("t", "p", "op");
	}

	@Test
	void defaultCategoriesUseConfiguredTtl() {
		assertEquals(86400, resolve(policy("LOW_VOLATILITY", null, null, null, null)).orElseThrow().ttlSeconds());
		assertEquals(3600, resolve(policy("MEDIUM_VOLATILITY", null, null, null, null)).orElseThrow().ttlSeconds());
		assertEquals(300, resolve(policy("HIGH_VOLATILITY", null, null, null, null)).orElseThrow().ttlSeconds());
	}

	@Test
	void noPolicyAndRealTimeNeverCache() {
		assertTrue(resolve(null).isEmpty());
		assertTrue(resolve(policy("REAL_TIME", 60, null, null, null)).isEmpty());
	}

	@Test
	void customNeedsEverythingElseIsNoPolicyOnRead() {
		var review = LocalDate.of(2027, 1, 1);
		assertEquals(600, resolve(policy("CUSTOM", 600, "why", "owner", review)).orElseThrow().ttlSeconds());
		assertTrue(resolve(policy("CUSTOM", null, "why", "owner", review)).isEmpty());
		assertTrue(resolve(policy("CUSTOM", 600, " ", "owner", review)).isEmpty());
		assertTrue(resolve(policy("CUSTOM", 600, "why", null, review)).isEmpty());
		assertTrue(resolve(policy("CUSTOM", 600, "why", "owner", null)).isEmpty());
	}

	@Test
	void effectiveTtlIsCappedByMax() {
		assertEquals(48 * 3600, resolve(policy("LOW_VOLATILITY", 10_000_000, null, null, null)).orElseThrow().ttlSeconds());
		assertEquals(10, resolve(policy("LOW_VOLATILITY", 10, null, null, null)).orElseThrow().ttlSeconds());
	}

	@Test
	void invalidPoliciesAreRejectedOnWrite() {
		assertThrows(IllegalArgumentException.class, () -> service.save(policy("CUSTOM", 60, null, "o", LocalDate.now()), "a"));
		assertThrows(IllegalArgumentException.class, () -> service.save(policy("BOGUS", 60, null, null, null), "a"));
		assertThrows(IllegalArgumentException.class, () -> service.save(policy("LOW_VOLATILITY", 0, null, null, null), "a"));
		verify(repo, never()).savePolicy(any(), any());
		service.save(policy("CUSTOM", 60, "why", "o", LocalDate.now()), "a");
		verify(repo).savePolicy(any(), any());
	}

}
