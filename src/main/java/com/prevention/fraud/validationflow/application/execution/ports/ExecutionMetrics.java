package com.prevention.fraud.validationflow.application.execution.ports;

/** Port for execution events worth counting; implemented outside application so it stays free of metrics libraries. */
public interface ExecutionMetrics {

	void nodeTimeout(String validator);

	void nodeError(String validator, String code);

	void nodeRetry(String validator);

	void nodeRejected(String validator);

	void executionFinished(String status, java.time.Duration duration);

	void nodeFinished(String type, String status, java.time.Duration duration);

}
