package com.prevention.fraud.validationflow.application.execution.history;

import com.prevention.fraud.validationflow.application.execution.ExecutionService;
import com.prevention.fraud.validationflow.application.execution.node.Maps;

import com.prevention.fraud.validationflow.application.execution.ports.ExecutionMetrics;
import com.prevention.fraud.validationflow.application.execution.ports.ExecutionRepository;
import com.prevention.fraud.validationflow.application.masking.LogMasker;
import com.prevention.fraud.validationflow.domain.execution.FlowExecution;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Records node history and transition audits, and owns the per-execution logging context (MDC). */
public class ExecutionRecorder {

	private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

	private final ExecutionRepository repository;

	private final ExecutionMetrics metrics;

	public ExecutionRecorder(ExecutionRepository repository, ExecutionMetrics metrics) {
		this.repository = repository;
		this.metrics = metrics;
	}

	/** Persists the attempt and logs it; payloads only appear in logs masked. */
	public void recordNode(FlowExecution ex, String nodeId, String type, int attempt, String status,
			Map<String, Object> output, Map<String, Object> error, Map<String, Object> ctx, Instant started) {
		error = error == null ? null : Maps.of(LogMasker.redact(error, ctx));
		repository.recordNode(ex.tenantId(), ex.id(), nodeId, type, attempt, status, LogMasker.secrets(output),
				LogMasker.secrets(error), LogMasker.secrets(ctx), started);
		metrics.nodeFinished(type, status, Duration.between(started, Instant.now()));
		MDC.put("nodeId", nodeId);
		MDC.put("status", status);
		try {
			log.info("node attempt={} type={} output={} error={}", attempt, type, LogMasker.mask(output),
					LogMasker.mask(error));
		}
		finally {
			MDC.remove("nodeId");
			MDC.remove("status");
		}
	}

	public void auditTransition(FlowExecution ex, String nodeId, Object to, Boolean result, Object observed) {
		repository.audit(ex.tenantId(), ex.id(), "TRANSITION_EVALUATED", nodeId,
				Map.of("to", to, "result", result, "observed", String.valueOf(observed)));
	}

	/** Puts the execution fields in the MDC and returns the previous MDC to hand back to {@link #restoreMdc}. */
	public Map<String, String> enterMdc(FlowExecution ex, String correlationId) {
		var outer = MDC.getCopyOfContextMap();
		MDC.put("correlationId", String.valueOf(correlationId));
		MDC.put("executionId", ex.id().toString());
		MDC.put("flowKey", ex.flowKey());
		MDC.put("flowVersion", String.valueOf(ex.flowVersion()));
		return outer;
	}

	public void restoreMdc(Map<String, String> outer) {
		if (outer == null) {
			MDC.clear();
		}
		else {
			MDC.setContextMap(outer);
		}
	}

	public void executionFinished(String status, Duration duration) {
		metrics.executionFinished(status, duration);
	}

	public void finished(FlowExecution done) {
		MDC.put("status", done.status().name());
		log.info("execution finished");
	}

	public void failed(RuntimeException e, Map<String, Object> ctx) {
		log.warn("execution error class={} message={}", e.getClass().getName(),
				LogMasker.redact(e.getMessage(), ctx));
	}

}
