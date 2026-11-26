package dev.tanager.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tanager.application.port.out.Integration;
import dev.tanager.application.port.out.LaunchCommand;
import dev.tanager.application.port.out.LaunchCommand.EnvNames;
import dev.tanager.domain.history.ShellEnvironment;
import dev.tanager.domain.history.WorkspacePath;
import dev.tanager.domain.session.IntegrationMode;
import dev.tanager.domain.session.LaunchTemplate;
import dev.tanager.domain.session.ProfileId;
import dev.tanager.domain.session.ShellKind;
import dev.tanager.domain.session.ShellProfile;
import dev.tanager.domain.session.WorkingDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** WSL bash 에 셸 통합(rcfile)을 주입하는 기동 명령 계산(#55, ADR-0014). 순수 계산이라 OS 와 무관하게 돈다. */
class WindowsWslInjectionTest {

    // wsl.exe 는 -- 뒤를 기본 셸의 -c 로 넘긴다 — 따옴표가 없으면 공백이 든 경로(사용자 이름)가 잘린다(실측)
    private static final String RC_ARG = "\"$TANAGER_RC\"";
    // rcfile 을 못 읽으면(지워짐 · 권한) 통합 없는 M2 의 bash -i 로 물러선다 — 맨 bash 가 아니라 ~/.bashrc 를 읽는 셸
    private static final List<String> RC_OR_PLAIN_BASH =
            List.of("[", "-r", RC_ARG, "]", "&&", "exec", "bash", "--rcfile", RC_ARG, "-i", "||", "exec", "bash", "-i");
    private static final String WSL_EXE = "C:\\Windows\\System32\\wsl.exe";
    private static final String PS_EXE = "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe";
    private static final String CMD_EXE = "C:\\Windows\\System32\\cmd.exe";
    private static final String USER_PROFILE = "C:\\Users\\me";
    private static final String SCRIPT_NAME = "tanager.bash";

    private static final ShellEnvironment UBUNTU = new ShellEnvironment.Wsl("Ubuntu");
    private static final ShellProfile WSL = new ShellProfile(
            new ProfileId("wsl:Ubuntu"),
            "Ubuntu",
            ShellKind.BASH,
            UBUNTU,
            new LaunchTemplate(WSL_EXE, List.of("-d", "Ubuntu")),
            IntegrationMode.OSC7);
    private static final ShellProfile POWERSHELL = new ShellProfile(
            new ProfileId("powershell"),
            "Windows PowerShell",
            ShellKind.WINDOWS_POWERSHELL,
            new ShellEnvironment.Windows(),
            new LaunchTemplate(PS_EXE, List.of("-NoLogo")),
            IntegrationMode.OSC9_9);
    private static final ShellProfile CMD = new ShellProfile(
            new ProfileId("cmd"),
            "cmd",
            ShellKind.CMD,
            new ShellEnvironment.Windows(),
            new LaunchTemplate(CMD_EXE, List.of()),
            IntegrationMode.NONE);

    private static final Path INSTALLED_DIR = Path.of("integration-dir", "shell-integration");
    private static final Integration INSTALLED = new Integration.Installed(INSTALLED_DIR);
    private static final Integration UNAVAILABLE = new Integration.Unavailable("스크립트를 쓸 수 없다");

    private static WorkingDir wslDir(String raw) {
        return new WorkingDir.At(WorkspacePath.of(UBUNTU, raw).value().orElseThrow());
    }

    private static LaunchCommand of(
            ShellProfile profile, WorkingDir dir, Optional<String> wslenv, Integration integration) {
        return WindowsLaunchCommands.of(profile, dir, USER_PROFILE, wslenv, integration);
    }

    private static List<String> wslArgv(String cd) {
        List<String> argv = new ArrayList<>(List.of(WSL_EXE, "-d", "Ubuntu", "--cd", cd, "--"));
        argv.addAll(RC_OR_PLAIN_BASH);
        return argv;
    }

    @Test
    @DisplayName("기본 셸에 넘기는 인자에는 공백이 없다 — Windows 명령줄로 옮길 때 다시 감싸지 않아 기본 셸이 그대로 읽는다")
    void shellArgumentsHaveNoWhitespace() {
        // given / when
        List<String> argv =
                of(WSL, wslDir("/mnt/c/x"), Optional.empty(), INSTALLED).argv();

        // then
        assertThat(argv.subList(argv.indexOf("--") + 1, argv.size())).noneMatch(arg -> arg.matches(".*\\s.*"));
    }

    private static String rcfileOf(Path directory) {
        return directory.resolve(SCRIPT_NAME).toString();
    }

    @Test
    @DisplayName(
            "셸 통합이 설치돼 있으면 WSL 은 rcfile 을 읽을 수 있을 때 bash --rcfile \"$TANAGER_RC\" -i, 아니면 bash -i 로 띄우고 --cd 는 유지한다")
    void installedWslUsesRcfileAndKeepsCd() {
        // given / when
        LaunchCommand command = of(WSL, wslDir("/mnt/c/x"), Optional.empty(), INSTALLED);

        // then
        assertThat(command.argv()).containsExactlyElementsOf(wslArgv("/mnt/c/x"));
        assertThat(command.workingDirectory()).isEmpty();
    }

    @Test
    @DisplayName("홈에서 띄워도 --cd ~ 를 유지하고 rcfile 을 준다")
    void installedWslAtHomeKeepsTilde() {
        // given / when
        LaunchCommand command = of(WSL, new WorkingDir.Home(), Optional.empty(), INSTALLED);

        // then
        assertThat(command.argv()).containsExactlyElementsOf(wslArgv("~"));
    }

    @Test
    @DisplayName("TANAGER_RC 는 설치 폴더의 tanager.bash 경로이고 WSLENV 에 /p(경로 변환)로 올린다")
    void installedWslSetsRcfileVariableAndWslenv() {
        // given / when
        LaunchCommand command = of(WSL, wslDir("/mnt/c/x"), Optional.empty(), INSTALLED);

        // then
        assertThat(command.environment())
                .containsOnly(Map.entry("TANAGER_RC", rcfileOf(INSTALLED_DIR)), Map.entry("WSLENV", "TANAGER_RC/p"));
        assertThat(command.envNames()).isEqualTo(EnvNames.CASE_INSENSITIVE);
    }

    @Test
    @DisplayName("앱 환경에 WSLENV 가 이미 있으면 보존하고 뒤에 :TANAGER_RC/p 를 붙인다")
    void existingWslenvIsPreserved() {
        // given / when
        LaunchCommand command = of(WSL, wslDir("/mnt/c/x"), Optional.of("FOO/u"), INSTALLED);

        // then
        assertThat(command.environment()).containsEntry("WSLENV", "FOO/u:TANAGER_RC/p");
    }

    @Test
    @DisplayName("기존 WSLENV 가 공백뿐이거나 콜론으로 끝나도 빈 항목 없이 이어 붙인다")
    void blankOrTrailingSeparatorWslenvDoesNotLeaveEmptyEntry() {
        // given / when
        LaunchCommand blank = of(WSL, wslDir("/mnt/c/x"), Optional.of("  "), INSTALLED);
        LaunchCommand trailing = of(WSL, wslDir("/mnt/c/x"), Optional.of("FOO/u:"), INSTALLED);

        // then
        assertThat(blank.environment()).containsEntry("WSLENV", "TANAGER_RC/p");
        assertThat(trailing.environment()).containsEntry("WSLENV", "FOO/u:TANAGER_RC/p");
    }

    @Test
    @DisplayName("Tanager 안에서 Tanager 를 띄워 WSLENV 에 TANAGER_RC 가 이미 있으면 중복 없이 /p 하나만 남긴다")
    void existingRcfileEntryIsReplacedNotDuplicated() {
        // given / when
        LaunchCommand command = of(WSL, wslDir("/mnt/c/x"), Optional.of("TANAGER_RC/u:FOO/u"), INSTALLED);

        // then
        assertThat(command.environment()).containsEntry("WSLENV", "FOO/u:TANAGER_RC/p");
    }

    @Test
    @DisplayName("셸 통합을 쓸 수 없으면 M2 와 같은 명령이고 환경 변수를 더하지 않는다(WSLENV 도 건드리지 않는다)")
    void unavailableKeepsPlainBash() {
        // given / when
        LaunchCommand command = of(WSL, wslDir("/mnt/c/x"), Optional.of("FOO/u"), UNAVAILABLE);

        // then
        assertThat(command.argv()).containsExactly(WSL_EXE, "-d", "Ubuntu", "--cd", "/mnt/c/x", "--", "bash", "-i");
        assertThat(command.environment()).isEmpty();
        assertThat(command.envNames()).isEqualTo(EnvNames.CASE_INSENSITIVE);
    }

    @Test
    @DisplayName("PowerShell · cmd 에는 셸 통합이 설치돼 있어도 WSL 용 TANAGER_RC · WSLENV 를 주지 않는다(두 셸의 주입은 #81)")
    void windowsShellsGetNoWslVariables() {
        // given / when
        LaunchCommand powershell = of(POWERSHELL, new WorkingDir.Home(), Optional.of("FOO/u"), INSTALLED);
        LaunchCommand cmd = of(CMD, new WorkingDir.Home(), Optional.of("FOO/u"), INSTALLED);

        // then
        assertThat(powershell.argv()).startsWith(PS_EXE, "-NoLogo").doesNotContain("--cd", "--");
        assertThat(powershell.environment()).doesNotContainKeys("TANAGER_RC", "WSLENV");
        assertThat(cmd.argv()).containsExactly(CMD_EXE);
        assertThat(cmd.environment()).doesNotContainKeys("TANAGER_RC", "WSLENV");
    }

    @Test
    @DisplayName("모듈은 생성 때 읽은 환경의 WSLENV 를 기동 명령에 병합한다")
    void moduleReadsWslenvFromEnvironment() {
        // given
        WindowsShellCatalog catalog = new WindowsShellCatalog(
                new FakeCommandRunner(new CommandOutcome.NotStarted("x")),
                key -> Optional.empty(),
                Duration.ofSeconds(1));
        WindowsPlatformModule module = new WindowsPlatformModule(
                catalog, key -> key.equals("WSLENV") ? Optional.of("FOO/u") : Optional.empty(), "C:\\Users\\me");

        // when
        LaunchCommand command = module.launchCommand(WSL, wslDir("/mnt/c/x"), INSTALLED);

        // then
        assertThat(command.environment())
                .containsOnly(
                        Map.entry("TANAGER_RC", rcfileOf(INSTALLED_DIR)), Map.entry("WSLENV", "FOO/u:TANAGER_RC/p"));
        assertThat(command.argv()).containsExactlyElementsOf(wslArgv("/mnt/c/x"));
    }

    @Test
    @DisplayName("환경에 WSLENV 가 없는 모듈은 TANAGER_RC/p 만 준다")
    void moduleWithoutWslenvUsesOnlyRcfileEntry() {
        // given
        WindowsShellCatalog catalog = new WindowsShellCatalog(
                new FakeCommandRunner(new CommandOutcome.NotStarted("x")),
                key -> Optional.empty(),
                Duration.ofSeconds(1));
        WindowsPlatformModule module = new WindowsPlatformModule(catalog, key -> Optional.empty(), "C:\\Users\\me");

        // when
        LaunchCommand command = module.launchCommand(WSL, new WorkingDir.Home(), INSTALLED);

        // then
        assertThat(command.environment()).containsEntry("WSLENV", "TANAGER_RC/p");
    }

    @Provide
    Arbitrary<List<String>> wslenvEntries() {
        return Arbitraries.of(
                        "FOO/u",
                        "BAR",
                        "PATH/l",
                        "X/p",
                        "TANAGER_RC/u",
                        "tanager_rc/p",
                        "TANAGER_RC",
                        "TANAGER_RCX/p",
                        "한글/p")
                .list()
                .ofMaxSize(5);
    }

    @Provide
    Arbitrary<String> directoryNames() {
        return Arbitraries.strings()
                .withChars(' ', '\'', '#', '%', '&', '한', '글', 'a', 'Z', '1', '_', '-')
                .ofMinLength(1)
                .ofMaxLength(20)
                // Windows 경로 이름은 공백으로 끝날 수 없다(Path.of 가 거부). 점은 글자 집합에 없다
                .filter(s -> !s.endsWith(" "));
    }

    @Property
    @Label("어떤 기존 WSLENV 든 TANAGER_RC/p 가 정확히 하나, 맨 뒤에 있고 다른 항목은 순서대로 보존된다")
    void wslenvAlwaysEndsWithSingleRcfileEntry(@ForAll("wslenvEntries") List<String> entries) {
        // given
        String existing = String.join(":", entries);

        // when
        String merged = of(WSL, wslDir("/mnt/c/x"), Optional.of(existing), INSTALLED)
                .environment()
                .get("WSLENV");

        // then
        List<String> parts = Arrays.asList(merged.split(":"));
        assertThat(parts.getLast()).isEqualTo("TANAGER_RC/p");
        assertThat(parts.stream().filter(WindowsWslInjectionTest::isRcEntry)).containsExactly("TANAGER_RC/p");
        // TANAGER_RCX 처럼 이름만 비슷한 항목은 남는다 — 이름(/ 앞)이 정확히 같을 때만 지운다
        List<String> kept = entries.stream().filter(e -> !isRcEntry(e)).toList();
        assertThat(parts.subList(0, parts.size() - 1)).containsExactlyElementsOf(kept);
    }

    private static boolean isRcEntry(String entry) {
        int flags = entry.indexOf('/');
        return (flags < 0 ? entry : entry.substring(0, flags)).equalsIgnoreCase("TANAGER_RC");
    }

    @Property
    @Label("설치 폴더 이름이 공백 · 한글 · ' 를 담아도 TANAGER_RC 는 그 폴더의 tanager.bash 하나로 나가고 argv 는 같다")
    void rcfileVariableSurvivesUnusualDirectoryNames(@ForAll("directoryNames") String name) {
        // given
        Path directory = Path.of("appdata", name, "shell-integration");

        // when
        LaunchCommand command = of(WSL, wslDir("/mnt/c/x"), Optional.empty(), new Integration.Installed(directory));

        // then
        assertThat(command.environment()).containsEntry("TANAGER_RC", rcfileOf(directory));
        assertThat(command.environment().get("TANAGER_RC")).contains(name).endsWith(SCRIPT_NAME);
        assertThat(command.argv()).containsExactlyElementsOf(wslArgv("/mnt/c/x"));
    }
}
