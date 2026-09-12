package com.personal.baton.watch.bootstrap;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
    private static final String ROOT = "com.personal.baton.watch";
    private static final String DOMAIN = ROOT + ".domain..";
    private static final String APPLICATION = ROOT + ".application..";
    private static final String WEB = ROOT + ".adapter.in.web..";
    private static final String PERSISTENCE = ROOT + ".adapter.out.persistence..";
    private static final String EXTERNAL = ROOT + ".adapter.out.external..";
    private static final String BOOTSTRAP = ROOT + ".bootstrap..";
    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    @Test
    void dependenciesFollowTheModuleLayers() {
        layeredArchitecture().consideringOnlyDependenciesInLayers()
                .layer("Domain").definedBy(DOMAIN)
                .layer("Application").definedBy(APPLICATION)
                .layer("Web").definedBy(WEB)
                .layer("Persistence").definedBy(PERSISTENCE)
                .layer("External").definedBy(EXTERNAL)
                .layer("Bootstrap").definedBy(BOOTSTRAP)
                .whereLayer("Domain").mayOnlyBeAccessedByLayers(
                        "Application", "Web", "Persistence", "External", "Bootstrap")
                .whereLayer("Application").mayOnlyBeAccessedByLayers(
                        "Web", "Persistence", "External", "Bootstrap")
                .whereLayer("Web").mayOnlyBeAccessedByLayers("Bootstrap")
                .whereLayer("Persistence").mayOnlyBeAccessedByLayers("Bootstrap")
                .whereLayer("External").mayOnlyBeAccessedByLayers("Bootstrap")
                .whereLayer("Bootstrap").mayNotBeAccessedByAnyLayer()
                .ensureAllClassesAreContainedInArchitecture()
                .as("계층 역방향 의존과 어댑터 간 직접 의존 금지")
                .check(PRODUCTION);
    }

    @Test
    void domainAndApplicationStayIndependentOfFrameworks() {
        classes().that().resideInAnyPackage(DOMAIN, APPLICATION)
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", DOMAIN, APPLICATION)
                .as("도메인·애플리케이션의 프레임워크·어댑터 의존 금지")
                .check(PRODUCTION);
    }

    @Test
    void webUsesInputPortsInsteadOfServicesOrOutputPorts() {
        noClasses().that().resideInAPackage(WEB)
                .should().dependOnClassesThat().resideInAnyPackage(
                        ROOT + ".application..service..", ROOT + ".application..port.out..")
                .as("웹 어댑터는 서비스 구현체·출력 포트 대신 입력 포트 사용")
                .check(PRODUCTION);
    }
}
