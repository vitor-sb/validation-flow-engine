package com.prevention.fraud.validationflow.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.prevention.fraud.validationflow", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

	private static final String BASE = "com.prevention.fraud.validationflow";

	@ArchTest
	static final ArchRule domainIsIsolated = noClasses().that().resideInAPackage(BASE + ".domain..")
			.should().dependOnClassesThat().resideInAnyPackage(
					BASE + ".application..", BASE + ".adapter..", BASE + ".config..",
					"org.springframework..", "jakarta.persistence..")
			.allowEmptyShould(true);

	@ArchTest
	static final ArchRule applicationDoesNotDependOnAdapters = noClasses().that().resideInAPackage(BASE + ".application..")
			.should().dependOnClassesThat().resideInAPackage(BASE + ".adapter..")
			.allowEmptyShould(true);

}
