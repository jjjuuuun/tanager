package dev.tanager.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.lang.ArchRule;

/**
 * Tanager 의 아키텍처 규칙(ADR-0002, .claude/rules/architecture.md).
 * 기준 패키지를 받아 만들므로 실제 코드(dev.tanager)와 위반 픽스처(dev.tanager.archfixture.*)에 같은 규칙을 건다.
 */
final class ArchitectureRules {
    private ArchitectureRules() {}

    /** domain 은 순수 Java: I/O · UI · 동시성 · 외부 라이브러리 금지. */
    static ArchRule domainIsPure(String base) {
        return noClasses()
                .that()
                .resideInAPackage(base + ".domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "java.io..",
                        "java.nio..",
                        "java.net..",
                        "java.sql..",
                        "java.awt..",
                        "javax.swing..",
                        "java.util.concurrent..",
                        "com.pty4j..",
                        "com.jediterm..",
                        "tools.jackson..",
                        "com.formdev..")
                .orShould()
                .dependOnClassesThat()
                .areAssignableTo(Thread.class)
                .because("domain 은 순수 Java 여야 한다 — I/O · UI · 스레드는 어댑터 · 플랫폼 · StateActor 의 일");
    }

    /** domain ← application ← (adapter · platform · ui) ← bootstrap. */
    static ArchRule layersRespectDirection(String base) {
        return layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .withOptionalLayers(true)
                .layer("Domain")
                .definedBy(base + ".domain..")
                .layer("Application")
                .definedBy(base + ".application..")
                .layer("Adapter")
                .definedBy(base + ".adapter..")
                .layer("Platform")
                .definedBy(base + ".platform..")
                .layer("Ui")
                .definedBy(base + ".ui..")
                .layer("Bootstrap")
                .definedBy(base + ".bootstrap..")
                .whereLayer("Bootstrap")
                .mayNotBeAccessedByAnyLayer()
                .whereLayer("Adapter")
                .mayOnlyBeAccessedByLayers("Bootstrap")
                .whereLayer("Platform")
                .mayOnlyBeAccessedByLayers("Bootstrap")
                .whereLayer("Ui")
                .mayOnlyBeAccessedByLayers("Bootstrap")
                .whereLayer("Application")
                .mayOnlyBeAccessedByLayers("Adapter", "Platform", "Ui", "Bootstrap")
                .whereLayer("Domain")
                .mayOnlyBeAccessedByLayers("Application", "Adapter", "Platform", "Ui", "Bootstrap")
                .because("의존 방향은 ADR-0002 — 바깥이 안쪽만 안다");
    }

    /** 어댑터끼리는 서로 모른다(application 의 포트로만 이어진다). */
    static ArchRule adaptersAreIndependent(String base) {
        return slices().matching(base + ".adapter.(*)..").should().notDependOnEachOther();
    }

    /** OS 별 플랫폼 모듈끼리는 서로 모른다(macOS 추가 시 다른 모듈을 바꾸지 않기 위해, NFR-04). */
    static ArchRule platformsAreIndependent(String base) {
        return slices().matching(base + ".platform.(*)..").should().notDependOnEachOther();
    }
}
