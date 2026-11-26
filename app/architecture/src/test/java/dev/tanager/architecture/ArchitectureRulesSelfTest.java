package dev.tanager.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 규칙이 "코드가 없어서 통과"하는 것이 아니라 실제로 위반을 잡는지, 일부러 어긴 픽스처로 확인한다.
 * 픽스처: src/test/java/dev/tanager/archfixture/** (테스트 클래스라 ArchitectureTest 의 대상에서는 빠진다)
 */
class ArchitectureRulesSelfTest {
    private static final String FIXTURE = "dev.tanager.archfixture";
    private static JavaClasses fixtures;

    @BeforeAll
    static void importFixtures() {
        fixtures = new ClassFileImporter().importPackages(FIXTURE);
    }

    @Test
    void domainPurityCatchesSwingIoAndThreads() {
        EvaluationResult result = ArchitectureRules.domainIsPure(FIXTURE).evaluate(fixtures);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anyMatch(d -> d.contains("javax.swing"))
                .anyMatch(d -> d.contains("java.io.File"))
                .anyMatch(d -> d.contains("java.lang.Thread"));
    }

    @Test
    void domainPurityAllowsOnlyStandardCharsetsFromJavaNio() {
        EvaluationResult result = ArchitectureRules.domainIsPure(FIXTURE).evaluate(fixtures);

        assertThat(result.getFailureReport().getDetails())
                .anyMatch(d -> d.contains("NioFilesInDomain") && d.contains("java.nio.file"))
                .anyMatch(d -> d.contains("CharsetInDomain") && d.contains("java.nio.charset.Charset"))
                .noneMatch(d -> d.contains("StandardCharsetsInDomain"));
    }

    @Test
    void layerRuleCatchesApplicationUsingAdapter() {
        EvaluationResult result =
                ArchitectureRules.layersRespectDirection(FIXTURE).evaluate(fixtures);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anyMatch(d -> d.contains("UsesAdapter") && d.contains("SqlStore"));
    }

    @Test
    void adapterIndependenceCatchesCrossAdapterUse() {
        EvaluationResult result =
                ArchitectureRules.adaptersAreIndependent(FIXTURE).evaluate(fixtures);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails()).anyMatch(d -> d.contains("ReachesIntoOtherAdapter"));
    }

    @Test
    void platformIndependenceCatchesCrossPlatformUse() {
        EvaluationResult result =
                ArchitectureRules.platformsAreIndependent(FIXTURE).evaluate(fixtures);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails()).anyMatch(d -> d.contains("BorrowsWindowsCode"));
    }

    @Test
    void osDetectionRuleCatchesLibrarySniffersOutsideBootstrap() {
        EvaluationResult result =
                ArchitectureRules.onlyBootstrapDetectsOs(FIXTURE).evaluate(fixtures);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anyMatch(d -> d.contains("SniffsOs") && d.contains("com.sun.jna.Platform"))
                .anyMatch(d -> d.contains("SniffsOs") && d.contains("SystemInfo"))
                .noneMatch(d -> d.contains("ChoosesPlatform"));
    }

    @Test
    void pty4jRuleCatchesUiUsingPty4jButAllowsPtyAdapter() {
        EvaluationResult result =
                ArchitectureRules.onlyPtyAdapterUsesPty4j(FIXTURE).evaluate(fixtures);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anyMatch(d -> d.contains("OpensPty") && d.contains("com.pty4j"))
                .noneMatch(d -> d.contains("LegitPty"));
    }

    @Test
    void colorRuleCatchesLiteralsOutsideThemeButAllowsThemePackage() {
        EvaluationResult result =
                ArchitectureRules.colorsOnlyFromThemeTokens(FIXTURE).evaluate(fixtures);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anyMatch(d -> d.contains("PaintsWithLiteral") && d.contains("java.awt.Color.<init>"))
                .anyMatch(d -> d.contains("PaintsWithLiteral") && d.contains("RED"))
                .noneMatch(d -> d.contains("ThemeOwnsColors"));
    }

    @Test
    void colorRuleCatchesFactoriesReferencesAndPalettes() {
        // when
        List<String> details = ArchitectureRules.colorsOnlyFromThemeTokens(FIXTURE)
                .evaluate(fixtures)
                .getFailureReport()
                .getDetails();

        // then
        assertThat(details)
                .as("Color.decode 호출")
                .anyMatch(d -> d.contains("decoded()") && d.contains("java.awt.Color.decode"));
        assertThat(details)
                .as("new ColorUIResource")
                .anyMatch(d -> d.contains("uiResource()") && d.contains("javax.swing.plaf.ColorUIResource.<init>"));
        assertThat(details)
                .as("TerminalColor.rgb")
                .anyMatch(d -> d.contains("terminalRgb()") && d.contains("com.jediterm.terminal.TerminalColor.rgb"));
        assertThat(details)
                .as("Color::new")
                .anyMatch(d -> d.contains("constructorRef()") && d.contains("java.awt.Color.<init>"));
        assertThat(details)
                .as("Color::decode")
                .anyMatch(d -> d.contains("decodeRef()") && d.contains("java.awt.Color.decode"));
        assertThat(details)
                .as("ColorPaletteImpl.XTERM_PALETTE")
                .anyMatch(d -> d.contains("palette()") && d.contains("XTERM_PALETTE"));
        assertThat(details)
                .as("SystemColor")
                .anyMatch(d -> d.contains("system()") && d.contains("java.awt.SystemColor"));
        assertThat(details)
                .as("new Rgb")
                .anyMatch(d -> d.contains("rgb()") && d.contains("archfixture.ui.theme.Rgb.<init>"));
        assertThat(details)
                .as("Rgb.parse")
                .anyMatch(d -> d.contains("parsed()") && d.contains("archfixture.ui.theme.Rgb.parse"));
    }

    @Test
    void colorRuleAllowsOnlyThemePackageItselfAndIndexedColors() {
        List<String> details = ArchitectureRules.colorsOnlyFromThemeTokens(FIXTURE)
                .evaluate(fixtures)
                .getFailureReport()
                .getDetails();

        assertThat(details).as("ui.theme 하위 패키지는 허용 범위가 아니다").anyMatch(d -> d.contains("NestedThemeColors"));
        assertThat(details).as("TerminalColor.index · 받은 색 읽기는 허용").noneMatch(d -> d.contains("UsesIndexedColor"));
        assertThat(details).as("ui.theme 자체는 허용").noneMatch(d -> d.contains("ThemeOwnsColors"));
    }
}
