package com.prevention.fraud.validationflow.application.validator;

import com.prevention.fraud.validationflow.domain.document.BrazilianDocuments;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Offline CPF/CNPJ check-digit validator, no external call. Node config {@code params.field} names the
 * {@code inputData} field holding the document.
 */
public class DocumentChecksumValidator implements ValidatorStrategy {

	private final String key;

	private final Predicate<String> check;

	private DocumentChecksumValidator(String key, Predicate<String> check) {
		this.key = key;
		this.check = check;
	}

	public static DocumentChecksumValidator cpf() {
		return new DocumentChecksumValidator("cpf-checksum", BrazilianDocuments::isValidCpf);
	}

	public static DocumentChecksumValidator cnpj() {
		return new DocumentChecksumValidator("cnpj-checksum", BrazilianDocuments::isValidCnpj);
	}

	@Override
	public String key() {
		return key;
	}

	@Override
	public ValidationResult execute(ValidationInput input) {
		Object params = input.config().get("params");
		Object field = params instanceof Map<?, ?> p ? p.get("field") : null;
		if (field == null) {
			throw new ValidatorException("INVALID_CONFIG", "params.field is required by " + key, false);
		}
		Object inputData = input.data().get("inputData");
		Object value = inputData instanceof Map<?, ?> m ? m.get(String.valueOf(field)) : null;
		if (value == null) {
			throw new ValidatorException("MISSING_INPUT", "input field '" + field + "' is required by " + key, false);
		}
		boolean valid = check.test(String.valueOf(value));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("valid", valid);
		return new ValidationResult(valid, out);
	}

	@Override
	public Map<String, Object> schema() {
		return Map.of("type", "object", "properties", Map.of("params", Map.of("type", "object", "properties",
				Map.of("field", Map.of("type", "string", "description", "inputData field holding the document")))));
	}

	@Override
	public Set<String> capabilities() {
		return Set.of("OFFLINE");
	}

}
