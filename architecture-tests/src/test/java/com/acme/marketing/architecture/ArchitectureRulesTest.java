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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
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

    @Test
    void sqlStatementsStayInMapperXml() throws IOException {
        Path workspace = workspaceRoot();
        Pattern inlineSql = Pattern.compile(
                "(?is)\\\"\\s*(select\\s|with\\s|insert\\s+into\\s|update\\s+mk_|delete\\s+from\\s)");
        List<String> violations = new ArrayList<>();
        for (Path sourceRoot : List.of(workspace.resolve("services"), workspace.resolve("platform-web"))) {
            try (var files = Files.walk(sourceRoot)) {
                files.filter(path -> path.toString().contains("/src/main/java/"))
                        .filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> inlineSql.matcher(read(path)).find())
                        .forEach(path -> violations.add(workspace.relativize(path).toString()));
            }
        }
        assertTrue(violations.isEmpty(), "SQL 必须维护在 Mapper XML，Java 中发现内联 SQL: " + violations);
    }

    /** 同时兼容从仓库根目录和 architecture-tests 模块目录直接运行 Maven。 */
    private static Path workspaceRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(current.resolve("services"))) return current;
        Path parent = current.getParent();
        if (parent != null && Files.isDirectory(parent.resolve("services"))) return parent;
        throw new IllegalStateException("cannot locate marketing platform workspace");
    }

    private static String read(Path path) {
        try { return Files.readString(path); }
        catch (IOException failure) { throw new IllegalStateException("cannot inspect " + path, failure); }
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
    static final ArchRule MIGRATED_APPLICATIONS_DO_NOT_DEPEND_ON_PERSISTENCE_ADAPTERS = noClasses()
            .that().resideInAnyPackage(
                    "..audience.application..", "..decision.application..", "..engagement.application..",
                    "..eventgateway.application..", "..compiler.application..",
                    "..measurement.application..", "..journeyservice.application..",
                    "..control.application..", "..benefit.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.jdbc..", "org.apache.ibatis..", "..infrastructure.persistence..")
            .because("已迁移的应用层只依赖持久化端口，SQL 与 MyBatis 必须留在外部适配器");

    @ArchTest
    static final ArchRule DECISION_RUNTIME_DOES_NOT_DEPEND_ON_PERSISTENCE_ADAPTERS = noClasses()
            .that().resideInAPackage("..decision.runtime..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.jdbc..", "org.apache.ibatis..", "..infrastructure.persistence..")
            .because("决策运行时通过端口持久化投影和开关，不得感知数据库实现");

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
