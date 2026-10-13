package com.prevention.fraud.validationflow.adapter.out.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.prevention.fraud.validationflow.application.provider.ProviderException;

class EnvCredentialResolverTest {

	private static final String SECRET = "s3cr3t-value-123";
	private static final String REF = "MY_PROVIDER_TOKEN";

	private final EnvCredentialResolver resolver = new EnvCredentialResolver(Map.of(REF, SECRET, "EMPTY", " ")::get);

	@Test
	void resolvesTheVariableNamedByTheReference() {
		assertThat(resolver.resolve(REF)).isEqualTo(SECRET);
	}

	@Test
	void unresolvableReferenceFailsWithoutLeakingNameOrValue() {
		for (var ref : new String[] { "MISSING_VAR_NAME", "EMPTY", "", null }) {
			assertThatThrownBy(() -> resolver.resolve(ref))
					.isInstanceOfSatisfying(ProviderException.class, e -> {
						assertThat(e.code()).isEqualTo("PROVIDER_MISCONFIGURED");
						assertThat(e.retryable()).isFalse();
						assertThat(e.getMessage()).doesNotContain("MISSING_VAR_NAME", "EMPTY", SECRET);
						assertThat(e.getCause()).isNull();
					});
		}
	}

}
