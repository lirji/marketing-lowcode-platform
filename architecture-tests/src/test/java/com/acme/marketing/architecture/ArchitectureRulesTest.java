package com.acme.marketing.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

@AnalyzeClasses(packages = "com.acme.marketing", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {
    @Test
    void serviceLayersAreActuallyImported() {
        var imported = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.acme.marketing");
        assertTrue(imported.stream().anyMatch(javaClass -> javaClass.getPackageName().contains(".domain")),
                "architecture checks must see service domain classes");
        assertTrue(imported.stream().anyMatch(javaClass -> javaClass.getPackageName().contains(".application")),
                "architecture checks must see service application classes");
    }
    @ArchTest
    static final ArchRule DOMAIN_IS_FRAMEWORK_INDEPENDENT = classes()
            .that().resideInAnyPackage(
                    "..lowcode.model..", "..decision.model..", "..journey..", "..provider..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "java..", "javax.crypto..", "com.acme.marketing..")
            .because("portable domain and runtime contracts must not couple to Spring or vendor SDKs");

    @ArchTest
    static final ArchRule SERVICE_DOMAINS_DO_NOT_DEPEND_OUTWARD = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta.persistence..", "jakarta.servlet..",
                    "..application..", "..interfaces..", "..infrastructure..")
            .because("DDD domain code owns policy and must remain independent of delivery and adapters");

    @ArchTest
    static final ArchRule APPLICATIONS_DO_NOT_DEPEND_ON_HTTP_INTERFACES = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..interfaces..")
            .because("HTTP controllers adapt inward to application use cases");

    @ArchTest
    static final ArchRule TOP_LEVEL_PACKAGES_HAVE_NO_CYCLES = slices()
            .matching("com.acme.marketing.(*)..")
            .should().beFreeOfCycles();

    @ArchTest
    static final ArchRule EXCEPTIONS_USE_EXCEPTION_SUFFIX = classes()
            .that().areAssignableTo(RuntimeException.class)
            .and().resideInAPackage("com.acme.marketing..")
            .should().haveSimpleNameEndingWith("Exception");
}
