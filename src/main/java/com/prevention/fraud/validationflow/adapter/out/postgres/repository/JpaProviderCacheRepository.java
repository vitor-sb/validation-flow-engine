package com.prevention.fraud.validationflow.adapter.out.postgres.repository;

import java.util.Optional;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.prevention.fraud.validationflow.adapter.out.postgres.entity.ProviderCacheEntryEntity;
import com.prevention.fraud.validationflow.adapter.out.postgres.entity.ProviderCachePolicyEntity;
import com.prevention.fraud.validationflow.application.provider.ProviderCacheEntry;
import com.prevention.fraud.validationflow.application.provider.ProviderCachePolicy;
import com.prevention.fraud.validationflow.application.provider.ProviderCacheRepository;

@Repository
public class JpaProviderCacheRepository implements ProviderCacheRepository {

	private final ProviderCacheEntryJpaRepository entries;

	private final ProviderCachePolicyJpaRepository policies;

	JpaProviderCacheRepository(ProviderCacheEntryJpaRepository entries, ProviderCachePolicyJpaRepository policies) {
		this.entries = entries;
		this.policies = policies;
	}

	@Override
	public Optional<ProviderCacheEntry> findEntry(String tenantId, String providerKey, String operation,
			String requestKey) {
		return entries.findByTenantIdAndProviderKeyAndOperationAndRequestKey(tenantId, providerKey, operation, requestKey)
			.map(e -> new ProviderCacheEntry(e.id, e.tenantId, e.providerKey, e.operation, e.requestKey, e.response,
					e.dataCategory, e.fetchedAt, e.validUntil, e.policyApplied));
	}

	@Override
	@Transactional
	public void saveEntry(ProviderCacheEntry c) {
		// keep the existing row's id so the unique key is replaced, not violated
		var e = entries.findByTenantIdAndProviderKeyAndOperationAndRequestKey(c.tenantId(), c.providerKey(),
				c.operation(), c.requestKey()).orElseGet(ProviderCacheEntryEntity::new);
		e.id = e.id != null ? e.id : c.id();
		e.tenantId = c.tenantId();
		e.providerKey = c.providerKey();
		e.operation = c.operation();
		e.requestKey = c.requestKey();
		e.response = c.response();
		e.dataCategory = c.dataCategory();
		e.fetchedAt = c.fetchedAt();
		e.validUntil = c.validUntil();
		e.policyApplied = c.policyApplied();
		entries.save(e);
	}

	@Override
	public Optional<ProviderCachePolicy> findPolicy(String tenantId, String providerKey, String operation) {
		return policies.findById(new ProviderCachePolicyEntity.Key(tenantId, providerKey, operation))
			.map(e -> new ProviderCachePolicy(e.id.tenantId(), e.id.providerKey(), e.id.operation(), e.dataCategory,
					e.ttlSeconds, e.fallbackOnErrorAllowed, e.justification, e.owner, e.reviewDate));
	}

	@Override
	public void savePolicy(ProviderCachePolicy p) {
		var e = new ProviderCachePolicyEntity();
		e.id = new ProviderCachePolicyEntity.Key(p.tenantId(), p.providerKey(), p.operation());
		e.dataCategory = p.dataCategory();
		e.ttlSeconds = p.ttlSeconds();
		e.fallbackOnErrorAllowed = p.fallbackOnErrorAllowed();
		e.justification = p.justification();
		e.owner = p.owner();
		e.reviewDate = p.reviewDate();
		policies.save(e);
	}

}
