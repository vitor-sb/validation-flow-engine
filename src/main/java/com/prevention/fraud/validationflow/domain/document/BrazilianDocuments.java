package com.prevention.fraud.validationflow.domain.document;

import java.util.Locale;

/** Offline check-digit validation for CPF and CNPJ (including the alphanumeric CNPJ in force since July 2026). */
public final class BrazilianDocuments {

	private static final int[] CNPJ_WEIGHTS_12 = { 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2 };

	private static final int[] CNPJ_WEIGHTS_13 = { 6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2 };

	private BrazilianDocuments() {
	}

	/** Strips the usual punctuation ({@code . / -} and spaces) and upper-cases; other characters are kept. */
	public static String normalize(String s) {
		return s == null ? "" : s.replaceAll("[.\\-/\\s]", "").toUpperCase(Locale.ROOT);
	}

	public static boolean isValidCpf(String raw) {
		String d = normalize(raw);
		if (!d.matches("\\d{11}") || allSame(d)) {
			return false;
		}
		return checkDigit(d, 9, 10) == d.charAt(9) - '0' && checkDigit(d, 10, 11) == d.charAt(10) - '0';
	}

	/** 12 alphanumeric characters followed by 2 numeric check digits; all-numeric CNPJs are a subset. */
	public static boolean isValidCnpj(String raw) {
		String d = normalize(raw);
		if (!d.matches("[0-9A-Z]{12}\\d{2}") || allSame(d)) {
			return false;
		}
		return cnpjDigit(d, CNPJ_WEIGHTS_12) == d.charAt(12) - '0' && cnpjDigit(d, CNPJ_WEIGHTS_13) == d.charAt(13) - '0';
	}

	private static int checkDigit(String d, int length, int firstWeight) {
		int sum = 0;
		for (int i = 0; i < length; i++) {
			sum += (d.charAt(i) - '0') * (firstWeight - i);
		}
		int r = sum % 11;
		return r < 2 ? 0 : 11 - r;
	}

	private static int cnpjDigit(String d, int[] weights) {
		int sum = 0;
		for (int i = 0; i < weights.length; i++) {
			sum += (d.charAt(i) - '0') * weights[i]; // '0'..'9' -> 0..9, 'A'..'Z' -> 17..42, as the RFB specifies
		}
		int r = sum % 11;
		return r < 2 ? 0 : 11 - r;
	}

	private static boolean allSame(String d) {
		return d.chars().distinct().count() == 1;
	}

}
