package com.prevention.fraud.validationflow.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.constructors;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

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

	@ArchTest
	static final ArchRule applicationIsFreeOfMicrometer = noClasses().that().resideInAPackage(BASE + ".application..")
			.should().dependOnClassesThat().resideInAPackage("io.micrometer..")
			.allowEmptyShould(true);

	@ArchTest
	static final ArchRule noCyclesBetweenLayers = slices().assignedFrom(new SliceAssignment() {
		@Override
		public SliceIdentifier getIdentifierOf(JavaClass c) {
			// config is left out: it wires adapters and exposes the authenticated principal that controllers receive
			for (String layer : new String[] { "domain", "application", "adapter" }) {
				if (c.getPackageName().startsWith(BASE + "." + layer)) {
					return SliceIdentifier.of(layer);
				}
			}
			return SliceIdentifier.ignore();
		}

		@Override
		public String getDescription() {
			return "layers";
		}
	}).should().beFreeOfCycles();

	@ArchTest
	static final ArchRule noCyclesBetweenApplicationFeatures = slices().matching(BASE + ".application.(*)..")
			.should().beFreeOfCycles();

	@ArchTest
	static final ArchRule noCyclesBetweenDomainFeatures = slices().matching(BASE + ".domain.(*)..")
			.should().beFreeOfCycles();

	@ArchTest
	static final ArchRule applicationIsFreeOfInfrastructure = noClasses().that().resideInAPackage(BASE + ".application..")
			.should().dependOnClassesThat().resideInAnyPackage(
					"org.springframework..", "jakarta..", "javax.sql..", "java.sql..", "com.fasterxml..", "tools.jackson..")
			.allowEmptyShould(true);

	@ArchTest
	static final ArchRule persistenceApiStaysInPostgresAdapter = noClasses().that().resideOutsideOfPackage(BASE + ".adapter.out.postgres..")
			.should().dependOnClassesThat().resideInAnyPackage("jakarta.persistence..", "org.springframework.data..")
			.allowEmptyShould(true);

	@ArchTest
	static final ArchRule entitiesStayInPostgresAdapter = noClasses().that().resideOutsideOfPackage(BASE + ".adapter.out.postgres..")
			.should().dependOnClassesThat().resideInAPackage(BASE + ".adapter.out.postgres.entity..");

	@ArchTest
	static final ArchRule portsAreInterfaces = classes().that().resideInAPackage(BASE + ".application..ports")
			.and().areTopLevelClasses().and().haveSimpleNameNotEndingWith("package-info")
			.should().beInterfaces();

	@ArchTest
	static final ArchRule handlersAreNamedAsHandlers = classes().that().implement(BASE + ".application.execution.node.NodeHandler")
			.should().haveNameMatching(".*(Handler|NodeHandlers\\$\\w+)");

	@ArchTest
	static final ArchRule executionServiceHasAtMostFourDependencies = constructors().that().areDeclaredIn(BASE + ".application.execution.ExecutionService")
			.should(new com.tngtech.archunit.lang.ArchCondition<com.tngtech.archunit.core.domain.JavaConstructor>("have at most 4 parameters") {
				@Override
				public void check(com.tngtech.archunit.core.domain.JavaConstructor c, com.tngtech.archunit.lang.ConditionEvents events) {
					events.add(new com.tngtech.archunit.lang.SimpleConditionEvent(c, c.getRawParameterTypes().size() <= 4,
							c.getDescription() + " has " + c.getRawParameterTypes().size() + " parameters"));
				}
			});

}
