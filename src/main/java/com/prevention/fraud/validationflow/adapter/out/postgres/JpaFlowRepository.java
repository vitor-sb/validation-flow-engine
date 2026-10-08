package com.prevention.fraud.validationflow.adapter.out.postgres;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import com.prevention.fraud.validationflow.application.flow.ports.FlowRepository;
import com.prevention.fraud.validationflow.domain.flow.FlowDefinition;
import com.prevention.fraud.validationflow.domain.flow.FlowStatus;

@Repository
class JpaFlowRepository implements FlowRepository {

	private static final Sort LIST_ORDER = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("flowKey"),
			Sort.Order.desc("version"));

	private final FlowDefinitionJpaRepository jpa;

	JpaFlowRepository(FlowDefinitionJpaRepository jpa) {
		this.jpa = jpa;
	}

	@Override
	public int nextVersion(String tenantId, String flowKey) {
		return jpa.nextVersion(tenantId, flowKey);
	}

	@Override
	public FlowDefinition save(FlowDefinition f) {
		jpa.saveAndFlush(FlowDefinitionMapper.toEntity(f));
		return f;
	}

	@Override
	public Optional<FlowDefinition> findById(String tenantId, UUID id) {
		return jpa.findByTenantIdAndId(tenantId, id).map(FlowDefinitionMapper::toDomain);
	}

	@Override
	public List<FlowDefinition> findActive(String tenantId, String flowKey, String userType, String context) {
		var found = flowKey != null && !flowKey.isBlank()
				? jpa.findTop2ByTenantIdAndStatusAndFlowKey(tenantId, FlowStatus.ACTIVE, flowKey)
				: jpa.findTop2ByTenantIdAndStatusAndUserTypeAndContext(tenantId, FlowStatus.ACTIVE, userType, context);
		return found.stream().map(FlowDefinitionMapper::toDomain).toList();
	}

	@Override
	public Page<FlowDefinition> list(String tenantId, String flowKey, FlowStatus status, String userType,
			String context, int page, int size) {
		var result = jpa.search(tenantId, flowKey, status, userType, context, PageRequest.of(page, size, LIST_ORDER));
		return new Page<>(result.map(FlowDefinitionMapper::toDomain).getContent(), result.getTotalElements());
	}

	@Override
	@Transactional
	public boolean updateDraft(FlowDefinition f) {
		return jpa.updateDraft(f.tenantId(), f.id(), f.userType(), f.context(), f.displayName(), f.description(),
				f.graphDefinition(), f.inputContract(), f.metadata(), Instant.now()) == 1;
	}

	@Override
	@Transactional
	public boolean activate(String tenantId, UUID id) {
		var now = Instant.now();
		// archive first: the partial unique index only admits one ACTIVE per selector
		jpa.archiveActiveOfSelector(tenantId, id, now);
		if (jpa.activateDraft(tenantId, id, now) == 1) {
			return true;
		}
		TransactionAspectSupport.currentTransactionStatus().setRollbackOnly(); // not a DRAFT: undo the archive
		return false;
	}

	@Override
	@Transactional
	public boolean archive(String tenantId, UUID id) {
		return jpa.archive(tenantId, id, Instant.now()) == 1;
	}

}
