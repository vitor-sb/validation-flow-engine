package com.prevention.fraud.validationflow.domain.document;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BrazilianDocumentsTest {

	@Test
	void validatesCpf() {
		assertThat(BrazilianDocuments.isValidCpf("529.982.247-25")).isTrue();
		assertThat(BrazilianDocuments.isValidCpf("52998224725")).isTrue();
		assertThat(BrazilianDocuments.isValidCpf("529.982.247-24")).isFalse();
		assertThat(BrazilianDocuments.isValidCpf("111.111.111-11")).isFalse();
		assertThat(BrazilianDocuments.isValidCpf("123")).isFalse();
		assertThat(BrazilianDocuments.isValidCpf(null)).isFalse();
	}

	@Test
	void validatesNumericCnpj() {
		assertThat(BrazilianDocuments.isValidCnpj("11.222.333/0001-81")).isTrue();
		assertThat(BrazilianDocuments.isValidCnpj("11222333000181")).isTrue();
		assertThat(BrazilianDocuments.isValidCnpj("11.222.333/0001-82")).isFalse();
		assertThat(BrazilianDocuments.isValidCnpj("00000000000000")).isFalse();
		assertThat(BrazilianDocuments.isValidCnpj("1122233300018")).isFalse();
	}

	@Test
	void validatesAlphanumericCnpj() {
		assertThat(BrazilianDocuments.isValidCnpj("12.ABC.345/01DE-35")).isTrue();
		assertThat(BrazilianDocuments.isValidCnpj("12abc34501de35")).isTrue();
		assertThat(BrazilianDocuments.isValidCnpj("12.ABC.345/01DE-36")).isFalse();
		assertThat(BrazilianDocuments.isValidCnpj("12.ABC.345/01DE-3A")).isFalse();
	}

}
