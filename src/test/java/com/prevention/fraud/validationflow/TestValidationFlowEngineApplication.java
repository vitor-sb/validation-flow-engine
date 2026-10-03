package com.prevention.fraud.validationflow;

import org.springframework.boot.SpringApplication;

public class TestValidationFlowEngineApplication {

	public static void main(String[] args) {
		SpringApplication.from(ValidationFlowEngineApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
