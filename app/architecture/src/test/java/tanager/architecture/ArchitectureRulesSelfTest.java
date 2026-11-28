package tanager.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 규칙이 "코드가 없어서 통과"하는 것이 아니라 실제로 위반을 잡는지, 일부러 어긴 픽스처로 확인한다.
 * 픽스처: src/test/java/tanager/archfixture/** (테스트 클래스라 ArchitectureTest 의 대상에서는 빠진다)
 */
class ArchitectureRulesSelfTest {
    private static final String FIXTURE = "tanager.archfixture";
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
    void domainCycleRuleCatchesPackagesDependingOnEachOther() {
        EvaluationResult result =
                ArchitectureRules.domainPackagesAreFreeOfCycles(FIXTURE).evaluate(fixtures);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails()).anyMatch(d -> d.contains("CycleA") || d.contains("CycleB"));
    }

    @Test
    void domainCycleRuleAllowsOneWayDependency() {
        // when
        List<String> details = ArchitectureRules.domainPackagesAreFreeOfCycles(FIXTURE)
                .evaluate(fixtures)
                .getFailureReport()
                .getDetails();

        // then
        assertThat(details).as("한 방향 의존(onewaya → onewayb)은 순환이 아니다").noneMatch(d -> d.contains("OneWay"));
    }

    @Test
    void applicationCycleRuleCatchesPortAndStateDependingOnEachOther() {
        // when
        EvaluationResult result =
                ArchitectureRules.applicationPackagesAreFreeOfCycles(FIXTURE).evaluate(fixtures);

        // then
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anyMatch(d -> d.contains("EventsFromState") && d.contains("StateEvent"));
    }

    @Test
    void applicationCycleRuleAllowsOneWayAndKnownPlatformEdge() {
        // when
        List<String> details = ArchitectureRules.applicationPackagesAreFreeOfCycles(FIXTURE)
                .evaluate(fixtures)
                .getFailureReport()
                .getDetails();

        // then
        assertThat(details).as("service → port.in 한 방향").noneMatch(d -> d.contains("OneWayService"));
        assertThat(details).as("뺀 간선 port.out → platform").noneMatch(d -> d.contains("FixtureOs"));
    }

    @Test
    void uiStateRuleCatchesUiUsingStateButAllowsPorts() {
        // when
        EvaluationResult result =
                ArchitectureRules.uiUsesOnlyApplicationPorts(FIXTURE).evaluate(fixtures);

        // then
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anyMatch(d -> d.contains("TouchesState") && d.contains("StateEvent"))
                .noneMatch(d -> d.contains("UsesPort"));
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

    @Test
    @DisplayName("도구함 공급자 규칙은 toolkit 어댑터 밖의 구현을 잡고 안의 구현은 허용한다")
    void toolkitProviderRuleCatchesImplementationsOutsideToolkitAdapters() {
        // when
        EvaluationResult result =
                ArchitectureRules.toolkitProvidersLiveInToolkitAdapters(FIXTURE).evaluate(fixtures);

        // then
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anyMatch(d -> d.contains("ProvidesToolkit"))
                .noneMatch(d -> d.contains("LegitToolkit"));
    }

    @Test
    @DisplayName("대화 기록 공급자 규칙은 toolkit 어댑터 밖의 구현을 잡고 안의 구현은 허용한다")
    void conversationSourceRuleCatchesImplementationsOutsideToolkitAdapters() {
        // when
        EvaluationResult result = ArchitectureRules.conversationSourcesLiveInToolkitAdapters(FIXTURE)
                .evaluate(fixtures);

        // then
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anyMatch(d -> d.contains("RogueProvider"))
                .noneMatch(d -> d.contains("LegitConversations"));
    }

    @Test
    @DisplayName("네트워크 금지 규칙은 HttpClient 를 쓰는 클래스를 잡는다")
    void networkRuleCatchesHttpClient() {
        // when
        EvaluationResult result = ArchitectureRules.noNetworkAccess(FIXTURE).evaluate(fixtures);

        // then
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anyMatch(d -> d.contains("CallsHome") && d.contains("java.net.http.HttpClient"));
    }

    @Test
    @DisplayName("네트워크 금지 규칙은 소켓 · URL 연결 · TLS · 소켓 채널을 호출과 메서드 참조 모두 잡는다")
    void networkRuleCatchesSocketsUrlConnectionsTlsAndChannels() {
        // when
        List<String> details = ArchitectureRules.noNetworkAccess(FIXTURE)
                .evaluate(fixtures)
                .getFailureReport()
                .getDetails();

        // then
        assertThat(details).as("new Socket").anyMatch(d -> d.contains("socket()") && d.contains("java.net.Socket"));
        assertThat(details)
                .as("new ServerSocket")
                .anyMatch(d -> d.contains("server()") && d.contains("java.net.ServerSocket"));
        assertThat(details)
                .as("new DatagramSocket")
                .anyMatch(d -> d.contains("datagram()") && d.contains("java.net.DatagramSocket"));
        assertThat(details)
                .as("URL.openConnection")
                .anyMatch(d -> d.contains("connection(") && d.contains("java.net.URL.openConnection"));
        assertThat(details)
                .as("URL.openStream")
                .anyMatch(d -> d.contains("stream(") && d.contains("java.net.URL.openStream"));
        assertThat(details)
                .as("URL::openStream")
                .anyMatch(d -> d.contains("streamRef()") && d.contains("java.net.URL.openStream"));
        assertThat(details).as("javax.net.ssl").anyMatch(d -> d.contains("tls()") && d.contains("javax.net.ssl"));
        assertThat(details)
                .as("SocketChannel")
                .anyMatch(d -> d.contains("channel()") && d.contains("java.nio.channels.SocketChannel"));
    }

    @Test
    @DisplayName("네트워크 금지 규칙은 file:// URI 해석만 하는 클래스를 허용한다")
    void networkRuleAllowsFileUriParsing() {
        // when
        List<String> details = ArchitectureRules.noNetworkAccess(FIXTURE)
                .evaluate(fixtures)
                .getFailureReport()
                .getDetails();

        // then
        assertThat(details).noneMatch(d -> d.contains("ParsesFileUri"));
    }

    @Test
    @DisplayName("도구함 쓰기 금지 규칙은 toolkit 어댑터의 Files.writeString 을 잡는다")
    void toolkitWriteRuleCatchesWriteString() {
        // when
        EvaluationResult result =
                ArchitectureRules.toolkitAdaptersDoNotWriteFiles(FIXTURE).evaluate(fixtures);

        // then
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anyMatch(d -> d.contains("WritesSettings") && d.contains("java.nio.file.Files.writeString"));
    }

    @Test
    @DisplayName("도구함 쓰기 금지 규칙은 삭제 · 이동 · 디렉터리 생성 · 쓰기 스트림 · 파일 채널 · Jackson 쓰기를 잡는다")
    void toolkitWriteRuleCatchesOtherWritePaths() {
        // when
        List<String> details = ArchitectureRules.toolkitAdaptersDoNotWriteFiles(FIXTURE)
                .evaluate(fixtures)
                .getFailureReport()
                .getDetails();

        // then
        assertThat(details)
                .as("Files::delete")
                .anyMatch(d -> d.contains("deleteRef()") && d.contains("java.nio.file.Files.delete"));
        assertThat(details).as("Files.move").anyMatch(d -> d.contains("move(") && d.contains("Files.move"));
        assertThat(details)
                .as("Files.createDirectories")
                .anyMatch(d -> d.contains("mkdirs(") && d.contains("Files.createDirectories"));
        assertThat(details)
                .as("FileOutputStream")
                .anyMatch(d -> d.contains("outputStream(") && d.contains("java.io.FileOutputStream"));
        assertThat(details)
                .as("FileChannel")
                .anyMatch(d -> d.contains("channel(") && d.contains("java.nio.channels.FileChannel"));
        assertThat(details)
                .as("File.mkdirs")
                .anyMatch(d -> d.contains("legacyMkdirs(") && d.contains("java.io.File.mkdirs"));
        assertThat(details)
                .as("new PrintWriter(파일 이름)")
                .anyMatch(d -> d.contains("printer(") && d.contains("java.io.PrintWriter.<init>"));
        assertThat(details)
                .as("Jackson writeValue(Path)")
                .anyMatch(d -> d.contains("jackson(") && d.contains("writeValue"));
    }

    @Test
    @DisplayName("도구함 쓰기 금지 규칙은 읽기 · 메모리 안 쓰기와 toolkit 밖 어댑터의 쓰기를 허용한다")
    void toolkitWriteRuleAllowsReadsAndOtherAdapters() {
        // when
        List<String> details = ArchitectureRules.toolkitAdaptersDoNotWriteFiles(FIXTURE)
                .evaluate(fixtures)
                .getFailureReport()
                .getDetails();

        // then
        assertThat(details).as("읽기 전용 접근").noneMatch(d -> d.contains("ReadsSettings"));
        assertThat(details).as("저장소 어댑터의 쓰기").noneMatch(d -> d.contains("WritesState"));
    }

    @Test
    @DisplayName("도구함 컨텍스트 규칙은 domain.toolkit 이 layout 을 아는 것을 잡고 history · shared 는 허용한다")
    void toolkitContextRuleCatchesLayoutButAllowsHistoryAndShared() {
        // when
        EvaluationResult result = ArchitectureRules.domainToolkitKnowsOnlyHistoryAndShared(FIXTURE)
                .evaluate(fixtures);

        // then
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anyMatch(d -> d.contains("KnowsLayout") && d.contains("domain.layout.Pane"))
                .noneMatch(d -> d.contains("KnowsHistory"));
    }
}
