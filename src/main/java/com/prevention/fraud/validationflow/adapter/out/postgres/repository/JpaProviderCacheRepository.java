package com.prevention.fraud.validationflow.adapter.out.postgres.repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.prevention.fraud.validationflow.adapter.out.postgres.entity.ProviderCacheEntryEntity;
import com.prevention.fraud.validationflow.adapter.out.postgres.entity.ProviderCachePolicyEntity;
import com.prevention.fraud.validationflow.adapter.out.postgres.entity.ProviderConfigAuditEntity;
import com.prevention.fraud.validationflow.application.provider.ProviderCacheEntry;
import com.prevention.fraud.validationflow.application.provider.ProviderCachePolicy;
import com.prevention.fraud.validationflow.application.provider.ProviderCacheRepository;

@Repository
public class JpaProviderCacheRepository implements ProviderCacheRepository {

	private final ProviderCacheEntryJpaRepository entries;

	private final ProviderCachePolicyJpaRepository policies;

	private final ProviderConfigAuditJpaRepository audit;

	JpaProviderCacheRepository(ProviderCacheEntryJpaRepository entries, ProviderCachePolicyJpaRepository policies,
			ProviderConfigAuditJpaRepository audit) {
		this.audit = audit;
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
	@Transactional
	public int deleteEntriesExpiredBefore(java.time.Instant cutoff) {
		return entries.deleteExpiredBefore(cutoff);
	}

	@Override
	public Optional<ProviderCachePolicy> findPolicy(String tenantId, String providerKey, String operation) {
		return policies.findById(new ProviderCachePolicyEntity.Key(tenantId, providerKey, operation)).map(JpaProviderCacheRepository::toDomain);
	}

	@Override
	public List<ProviderCachePolicy> findPolicies(String tenantId, String providerKey) {
		return policies.findByIdTenantIdAndIdProviderKey(tenantId, providerKey).stream().map(JpaProviderCacheRepository::toDomain).toList();
	}

	@Override
	@Transactional
	public void savePolicy(ProviderCachePolicy p, String actor) {
		var key = new ProviderCachePolicyEntity.Key(p.tenantId(), p.providerKey(), p.operation());
		boolean created = !policies.existsById(key);
		var e = new ProviderCachePolicyEntity();
		e.id = key;
		e.dataCategory = p.dataCategory();
		e.ttlSeconds = p.ttlSeconds();
		e.fallbackOnErrorAllowed = p.fallbackOnErrorAllowed();
		e.justification = p.justification();
		e.owner = p.owner();
		e.reviewDate = p.reviewDate();
		e.keyFields = p.keyFields();
		policies.save(e);
		var a = new ProviderConfigAuditEntity();
		a.id = UUID.randomUUID();
		a.tenantId = p.tenantId();
		a.providerKey = p.providerKey();
		a.actor = actor;
		a.eventType = created ? "CACHE_POLICY_CREATED" : "CACHE_POLICY_UPDATED";
		var d = new LinkedHashMap<String, Object>();
		d.put("operation", p.operation());
		d.put("dataCategory", p.dataCategory());
		d.put("ttlSeconds", p.ttlSeconds());
		d.put("fallbackOnErrorAllowed", p.fallbackOnErrorAllowed());
		d.put("justification", p.justification());
		d.put("owner", p.owner());
		d.put("reviewDate", p.reviewDate() == null ? null : p.reviewDate().toString());
		d.put("keyFields", p.keyFields());
		a.details = d;
		audit.save(a);
	}

	private static ProviderCachePolicy toDomain(ProviderCachePolicyEntity e) {
		return new ProviderCachePolicy(e.id.tenantId(), e.id.providerKey(), e.id.operation(), e.dataCategory,
				e.ttlSeconds, e.fallbackOnErrorAllowed, e.justification, e.owner, e.reviewDate, e.keyFields);
	}

}
