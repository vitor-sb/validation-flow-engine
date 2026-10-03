package com.prevention.fraud.validationflow.domain;

/** One field of a flow's input contract. */
public record InputField(String name, String type, boolean required) {
}
