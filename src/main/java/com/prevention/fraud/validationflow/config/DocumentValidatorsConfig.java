package com.prevention.fraud.validationflow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.prevention.fraud.validationflow.application.validator.DocumentChecksumValidator;
import com.prevention.fraud.validationflow.application.validator.ValidatorStrategy;

/** Offline validators: no credentials, no network, always available. */
@Configuration
class DocumentValidatorsConfig {

	@Bean
	ValidatorStrategy cpfChecksumValidator() {
		return DocumentChecksumValidator.cpf();
	}

	@Bean
	ValidatorStrategy cnpjChecksumValidator() {
		return DocumentChecksumValidator.cnpj();
	}

}
