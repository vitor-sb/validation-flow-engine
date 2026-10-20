package com.prevention.fraud.validationflow.adapter.out.credential;

import java.util.function.Function;

import com.prevention.fraud.validationflow.application.provider.CredentialResolver;
import com.prevention.fraud.validationflow.application.provider.ProviderException;

/** Reads the secret from the environment variable named by the reference. */
public class EnvCredentialResolver implements CredentialResolver {

	private final Function<String, String> env;

	public EnvCredentialResolver() {
		this(System::getenv);
	}

	EnvCredentialResolver(Function<String, String> env) {
		this.env = env;
	}

	@Override
	public String resolve(String ref) {
		var value = ref == null || ref.isBlank() ? null : env.apply(ref);
		if (value == null || value.isBlank()) {
			// neither the reference nor any value goes into the message: it is persisted and logged
			throw new ProviderException("PROVIDER_MISCONFIGURED", "Provider credentials could not be resolved", false);
		}
		return value;
	}

}
