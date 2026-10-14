package com.prevention.fraud.validationflow.application.execution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.crypto.spec.SecretKeySpec;

import com.prevention.fraud.validationflow.application.execution.node.Maps;
import com.prevention.fraud.validationflow.application.flow.FlowException;
import com.prevention.fraud.validationflow.application.provider.CredentialResolver;
import com.prevention.fraud.validationflow.application.provider.DataProvider;
import com.prevention.fraud.validationflow.application.provider.ProviderCall;
import com.prevention.fraud.validationflow.application.provider.ProviderCallRepository;
import com.prevention.fraud.validationflow.application.provider.ProviderConfig;
import com.prevention.fraud.validationflow.application.provider.ProviderConfigService;
import com.prevention.fraud.validationflow.application.provider.ProviderException;
import com.prevention.fraud.validationflow.application.provider.ProviderMetrics;
import com.prevention.fraud.validationflow.application.validator.ProviderBackedValidator;

/** Accepts a provider's callback for a pending call and resumes the waiting execution with the normalized result. */
public class ProviderCallbackService {

	private static final Logger log = LoggerFactory.getLogger(ProviderCallbackService.class);

	public enum Outcome { UNAUTHORIZED, ACCEPTED, DUPLICATE }

	/** Hash compared when the call does not exist, so a missing call costs the same as a wrong token. */
	private static final byte[] DUMMY = ProviderCall.hashToken("none").getBytes(StandardCharsets.UTF_8);

	private final ProviderCallRepository calls;
	private final ProviderConfigService configs;
	private final Map<String, DataProvider> providers;
	private final CredentialResolver credentials;
	private final ExecutionService executions;
	private final Clock clock;
	private final ProviderMetrics metrics;

	public ProviderCallbackService(ProviderCallRepository calls, ProviderConfigService configs,
			Map<String, DataProvider> providers, CredentialResolver credentials, ExecutionService executions, Clock clock,
			ProviderMetrics metrics) {
		this.calls = calls;
		this.configs = configs;
		this.providers = providers;
		this.credentials = credentials;
		this.executions = executions;
		this.clock = clock;
		this.metrics = metrics;
	}

	/**
	 * {@code rawBody} is what the signature covers; {@code body} is its parsed form. UNAUTHORIZED never says whether the
	 * call exists. A call that is DEAD/FAILED, expired, or whose execution moved on is a 409.
	 */
	public Outcome accept(UUID callId, String token, String signature, byte[] rawBody, Map<String, Object> body) {
		var call = calls.findById(callId).orElse(null);
		byte[] expected = call == null ? DUMMY : call.callbackTokenHash().getBytes(StandardCharsets.UTF_8);
		byte[] given = ProviderCall.hashToken(token == null ? "" : token).getBytes(StandardCharsets.UTF_8);
		if (!MessageDigest.isEqual(expected, given) || call == null) {
			return Outcome.UNAUTHORIZED;
		}
		var config = configs.resolve(call.tenantId(), call.providerKey()).orElse(null);
		if (config != null && config.callbackSecretRef() != null && !signatureValid(config, signature, rawBody)) {
			metrics.callback(call.providerKey(), "unauthorized");
			return Outcome.UNAUTHORIZED;
		}
		if (call.status() == ProviderCall.Status.COMPLETED) {
			metrics.callback(call.providerKey(), "duplicate");
			return Outcome.DUPLICATE;
		}
		if (call.status() != ProviderCall.Status.PENDING) {
			metrics.callback(call.providerKey(), "conflict");
			throw FlowException.conflict("call is " + call.status());
		}
		if (config == null) {
			throw FlowException.conflict("provider is not enabled for this tenant");
		}
		if (!call.deadline().isAfter(clock.instant())) {
			metrics.callback(call.providerKey(), "conflict");
			return expire(call);
		}
		if (body == null) {
			metrics.callback(call.providerKey(), "invalid");
			throw FlowException.invalidInput("callback body is not a JSON object");
		}
		Map<String, Object> data;
		try {
			data = providers.get(call.providerKey()).normalizeCallback(config, body);
		}
		catch (ProviderException | UnsupportedOperationException e) {
			metrics.callback(call.providerKey(), "invalid");
			throw FlowException.invalidInput("callback body could not be processed");
		}
		try {
			var outcome = complete(call, data);
			metrics.callback(call.providerKey(), outcome.name().toLowerCase());
			return outcome;
		}
		catch (FlowException e) {
			metrics.callback(call.providerKey(), "conflict");
			throw e;
		}
	}

	/** Resumes the waiting execution with an already normalized result and marks the call COMPLETED; 409 (call FAILED) if it can no longer resume. */
	public Outcome complete(ProviderCall call, Map<String, Object> data) {
		var node = Maps.of(Maps.of(executions.get(call.tenantId(), call.executionId()).snapshot().get("nodes")).get(call.nodeId()));
		boolean success = ProviderBackedValidator.matches(Maps.of(node.get("config")).get("expect"), data);
		try {
			executions.resumeWithProviderResult(call.tenantId(), call.executionId(), call.nodeId(), call.id(), data, success);
		}
		catch (FlowException e) {
			// lost a race with a duplicate callback, or the execution expired/was cancelled before the answer arrived
			var now = calls.findById(call.id()).orElse(call);
			if (now.status() == ProviderCall.Status.COMPLETED) {
				return Outcome.DUPLICATE;
			}
			return expire(call);
		}
		calls.save(withStatus(call, ProviderCall.Status.COMPLETED));
		log.info("provider call completed executionId={} callId={} providerKey={} status=COMPLETED", call.executionId(), call.id(),
				call.providerKey());
		return Outcome.ACCEPTED;
	}

	private Outcome expire(ProviderCall call) {
		calls.save(call.withState(ProviderCall.Status.FAILED, call.attempts(), null, clock.instant(), "EXECUTION_ENDED"));
		log.warn("provider call failed executionId={} callId={} providerKey={} status=FAILED reason=EXECUTION_ENDED", call.executionId(),
				call.id(), call.providerKey());
		throw FlowException.conflict("call is no longer waiting");
	}

	private ProviderCall withStatus(ProviderCall c, ProviderCall.Status status) {
		return new ProviderCall(c.id(), c.tenantId(), c.executionId(), c.nodeId(), c.providerKey(), c.externalRef(), c.mode(),
				status, c.attempts(), null, c.deadline(), c.callbackTokenHash(), c.createdAt(), clock.instant());
	}

	private boolean signatureValid(ProviderConfig config, String signature, byte[] rawBody) {
		if (signature == null) {
			return false;
		}
		try {
			var mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(credentials.resolve(config.callbackSecretRef()).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			String hex = HexFormat.of().formatHex(mac.doFinal(rawBody));
			return MessageDigest.isEqual(hex.getBytes(StandardCharsets.UTF_8),
					signature.toLowerCase().getBytes(StandardCharsets.UTF_8));
		}
		catch (java.security.GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

}
