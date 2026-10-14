package com.prevention.fraud.validationflow.application.execution;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.prevention.fraud.validationflow.application.flow.FlowException;
import com.prevention.fraud.validationflow.application.provider.ProviderCall;
import com.prevention.fraud.validationflow.application.provider.ProviderCallRepository;
import com.prevention.fraud.validationflow.application.provider.ProviderResult;
import com.prevention.fraud.validationflow.domain.execution.ExecutionStatus;

/** Dead-letter handling: moves unfinished calls to DEAD and lets an operator put one back in PENDING. */
public class ProviderCallAdminService {

	private final ProviderCallRepository calls;
	private final ExecutionService executions;
	private final Clock clock;

	public ProviderCallAdminService(ProviderCallRepository calls, ExecutionService executions, Clock clock) {
		this.calls = calls;
		this.executions = executions;
		this.clock = clock;
	}

	/** FAILED calls and PENDING calls of executions that expired, were cancelled or ended become DEAD. */
	public int sweep() {
		return calls.sweepDead(clock.instant());
	}

	public List<ProviderCall> list(String tenantId, ProviderCall.Status status, int page, int size) {
		return calls.listByStatus(tenantId, status, page, size);
	}

	public long count(String tenantId, ProviderCall.Status status) {
		return calls.countByStatus(tenantId, status);
	}

	/** DEAD -> PENDING with attempts reset; 404 if the call is not the tenant's, 409 unless DEAD and its execution still WAITING at the node. */
	public ProviderCall retry(String tenantId, UUID id) {
		var call = calls.find(tenantId, id).orElseThrow(FlowException::callNotFound);
		if (call.status() != ProviderCall.Status.DEAD) {
			throw FlowException.conflict("call is " + call.status() + "; only DEAD calls can be retried");
		}
		var ex = executions.get(tenantId, call.executionId());
		if (ex.status() != ExecutionStatus.WAITING || !call.nodeId().equals(ex.waitingNodeId())) {
			throw FlowException.conflict("execution is no longer waiting for this call (status " + ex.status() + ")");
		}
		var now = clock.instant();
		var again = call.withState(ProviderCall.Status.PENDING, 0,
				call.mode() == ProviderResult.Mode.POLL ? now : null, now);
		calls.save(again);
		return again;
	}

}
