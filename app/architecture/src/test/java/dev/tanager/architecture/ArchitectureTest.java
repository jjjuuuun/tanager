package dev.tanager.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.Location;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 실제 제품 코드(테스트 클래스 제외)에 아키텍처 규칙을 건다. */
class ArchitectureTest {
    private static final String BASE = "dev.tanager";
    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(ArchitectureTest::isProductionCode)
                .importPackages(BASE);
    }

    /**
     * 제품 모듈의 jar(tanager-*.jar, ADR-0008)와 클래스 디렉터리는 넣고, 외부 라이브러리 jar 와 테스트 산출물은 뺀다.
     *
     * <p>Gradle 은 다른 프로젝트를 런타임 클래스패스에 jar 로 넣는다 — {@code DO_NOT_INCLUDE_JARS} 를 쓰면 제품 코드가 통째로
     * 빠진다. {@code DO_NOT_INCLUDE_TESTS} 는 {@code build/classes/...} 만 알아보고 WSL 의 {@code build/wsl/classes/...} 를
     * 놓쳐서, 테스트 산출물은 경로 조각으로 직접 거른다.
     */
    private static boolean isProductionCode(Location location) {
        boolean ours = !location.isJar() || location.contains("/tanager-");
        boolean testOutput = location.contains("/classes/java/test/")
                || location.contains("/classes/java/testFixtures/")
                || location.contains("test-fixtures")
                || location.contains("/archfixture/");
        return ours && !testOutput;
    }

    @Test
    @DisplayName("규칙이 공허하게 통과하지 않도록 실제 제품 클래스를 import 하고 테스트 클래스는 뺀다")
    void importsRealProductionClassesOnly() {
        assertThat(production.stream().map(JavaClass::getName))
                // 클래스가 있는 제품 모듈마다 대표 하나(persistence · toolkit:claude 는 아직 클래스가 없다 — 생기면 더한다)
                .contains(
                        "dev.tanager.domain.shared.Result",
                        "dev.tanager.application.port.out.PlatformModule",
                        "dev.tanager.adapter.pty.Pty4jLauncher",
                        "dev.tanager.platform.windows.WindowsPlatformModule",
                        "dev.tanager.platform.macos.MacPlatformModule",
                        "dev.tanager.ui.SwingUi",
                        "dev.tanager.bootstrap.TanagerApp")
                .noneMatch(name -> name.startsWith(BASE + ".archfixture."))
                .noneMatch(name -> name.startsWith(BASE + ".architecture."))
                .noneMatch(name -> name.endsWith("Test") || name.endsWith("Contract"));
    }

    @Test
    void domainIsPure() {
        ArchitectureRules.domainIsPure(BASE).allowEmptyShould(true).check(production);
    }

    @Test
    void layersRespectDirection() {
        ArchitectureRules.layersRespectDirection(BASE).check(production);
    }

    @Test
    void adaptersAreIndependent() {
        ArchitectureRules.adaptersAreIndependent(BASE).allowEmptyShould(true).check(production);
    }

    @Test
    void platformsAreIndependent() {
        ArchitectureRules.platformsAreIndependent(BASE).allowEmptyShould(true).check(production);
    }

    @Test
    void onlyPtyAdapterUsesPty4j() {
        ArchitectureRules.onlyPtyAdapterUsesPty4j(BASE).allowEmptyShould(true).check(production);
    }

    @Test
    void colorsOnlyFromThemeTokens() {
        ArchitectureRules.colorsOnlyFromThemeTokens(BASE).allowEmptyShould(true).check(production);
    }
}
