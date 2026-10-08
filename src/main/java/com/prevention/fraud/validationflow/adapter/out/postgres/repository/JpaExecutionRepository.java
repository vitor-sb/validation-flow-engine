package com.prevention.fraud.validationflow.adapter.out.postgres.repository;

import com.prevention.fraud.validationflow.adapter.out.postgres.entity.ExecutionAuditLogEntity;
import com.prevention.fraud.validationflow.adapter.out.postgres.mapper.ExecutionMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.prevention.fraud.validationflow.application.execution.ports.ExecutionRepository;
import com.prevention.fraud.validationflow.domain.execution.ExecutionStatus;
import com.prevention.fraud.validationflow.domain.execution.FlowExecution;
import com.prevention.fraud.validationflow.domain.execution.NodeExecution;

@Repository
public class JpaExecutionRepository implements ExecutionRepository {

	private final FlowExecutionJpaRepository executions;

	private final NodeExecutionJpaRepository nodes;

	private final ExecutionAuditLogJpaRepository audits;

	JpaExecutionRepository(FlowExecutionJpaRepository executions, NodeExecutionJpaRepository nodes,
			ExecutionAuditLogJpaRepository audits) {
		this.executions = executions;
		this.nodes = nodes;
		this.audits = audits;
	}

	@Override
	public void insert(FlowExecution e) {
		executions.saveAndFlush(ExecutionMapper.toEntity(e));
	}

	@Override
	@Transactional
	public boolean update(FlowExecution e) {
		return executions.update(e.tenantId(), e.id(), e.lockVersion(), e.status(), e.contextData(), e.result(),
				e.errorInfo(), e.completedAt()) == 1;
	}

	@Override
	public Optional<FlowExecution> findById(String tenantId, UUID id) {
		return executions.findByTenantIdAndId(tenantId, id).map(ExecutionMapper::toDomain);
	}

	@Override
	public void recordNode(String tenantId, UUID executionId, String nodeId, String nodeType, int attempt,
			String status, Map<String, Object> output, Map<String, Object> error, Map<String, Object> input,
			Instant startedAt) {
		nodes.saveAndFlush(ExecutionMapper.toEntity(tenantId, executionId, nodeId, nodeType, attempt, status, output,
				error, input, startedAt, Instant.now()));
	}

	@Override
	public List<NodeExecution> findNodes(String tenantId, UUID executionId) {
		return nodes.findByTenantIdAndExecutionIdOrderByStartedAtAscCompletedAtAscAttemptAsc(tenantId, executionId)
				.stream().map(ExecutionMapper::toDomain).toList();
	}

	@Override
	public List<FlowExecution> list(String tenantId, int limit, long offset) {
		return executions.page(tenantId, limit, offset).stream().map(ExecutionMapper::toDomain).toList();
	}

	@Override
	public long count(String tenantId) {
		return executions.countByTenantId(tenantId);
	}

	@Override
	public void audit(String tenantId, UUID executionId, String eventType, String nodeId,
			Map<String, Object> details) {
		var a = new ExecutionAuditLogEntity();
		a.id = UUID.randomUUID();
		a.tenantId = tenantId;
		a.executionId = executionId;
		a.eventType = eventType;
		a.nodeId = nodeId;
		a.details = details;
		audits.saveAndFlush(a);
	}

	@Override
	public List<FlowExecution> findRunningStartedBefore(Instant cutoff) {
		return executions.findByStatusAndStartedAtBefore(ExecutionStatus.RUNNING, cutoff).stream()
				.map(ExecutionMapper::toDomain).toList();
	}

}
