package dev.tanager.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tanager.domain.history.ShellEnvironment;
import dev.tanager.domain.session.IntegrationMode;
import dev.tanager.domain.session.LaunchTemplate;
import dev.tanager.domain.session.ProfileId;
import dev.tanager.domain.session.ShellKind;
import dev.tanager.domain.session.ShellProfile;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class WindowsShellCatalogTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final Function<String, Optional<String>> ENV =
            key -> Optional.ofNullable(Map.of("SystemRoot", "C:\\Windows", "ComSpec", "C:\\Windows\\system32\\cmd.exe")
                    .get(key));

    private static CommandOutcome two() {
        return new CommandOutcome.Exited(0, "Ubuntu-24.04\r\nDebian\r\n".getBytes(StandardCharsets.UTF_16LE));
    }

    private static List<ShellProfile> shells(CommandOutcome outcome) {
        return new WindowsShellCatalog(new FakeCommandRunner(outcome), ENV, TIMEOUT).shells();
    }

    private static List<String> ids(List<ShellProfile> profiles) {
        return profiles.stream().map(p -> p.id().value()).toList();
    }

    @Test
    @DisplayName("WSL 배포판(출력 순서) → powershell → cmd 순서로 돌려준다")
    void ordering() {
        assertThat(ids(shells(two()))).containsExactly("wsl:Ubuntu-24.04", "wsl:Debian", "powershell", "cmd");
    }

    @Test
    @DisplayName("WSL 프로파일은 wsl.exe -d 배포판, BASH, OSC7 이다")
    void wslProfile() {
        ShellProfile p = shells(two()).get(0);
        assertThat(p)
                .isEqualTo(new ShellProfile(
                        new ProfileId("wsl:Ubuntu-24.04"),
                        "Ubuntu-24.04",
                        ShellKind.BASH,
                        new ShellEnvironment.Wsl("Ubuntu-24.04"),
                        new LaunchTemplate("C:\\Windows\\System32\\wsl.exe", List.of("-d", "Ubuntu-24.04")),
                        IntegrationMode.OSC7));
    }

    @Test
    @DisplayName("powershell 과 cmd 프로파일 값이 정해진 대로다")
    void windowsProfiles() {
        List<ShellProfile> all = shells(two());
        assertThat(all.get(2))
                .isEqualTo(new ShellProfile(
                        new ProfileId("powershell"),
                        "Windows PowerShell",
                        ShellKind.WINDOWS_POWERSHELL,
                        new ShellEnvironment.Windows(),
                        new LaunchTemplate(
                                "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", List.of("-NoLogo")),
                        IntegrationMode.OSC9_9));
        assertThat(all.get(3))
                .isEqualTo(new ShellProfile(
                        new ProfileId("cmd"),
                        "cmd",
                        ShellKind.CMD,
                        new ShellEnvironment.Windows(),
                        new LaunchTemplate("C:\\Windows\\system32\\cmd.exe", List.of()),
                        IntegrationMode.OSC9_9));
    }

    static Stream<CommandOutcome> failures() {
        return Stream.of(
                new CommandOutcome.NotStarted("wsl.exe 없음"),
                new CommandOutcome.TimedOut(TIMEOUT),
                new CommandOutcome.Exited(-1, "WSL 이 설치되어 있지 않습니다".getBytes(StandardCharsets.UTF_16LE)),
                new CommandOutcome.Exited(0, new byte[0]));
    }

    @ParameterizedTest
    @MethodSource("failures")
    @DisplayName("WSL 탐지가 실패하거나 비면 WSL 항목만 빠진다")
    void wslFailureDropsOnlyWsl(CommandOutcome outcome) {
        assertThat(ids(shells(outcome))).containsExactly("powershell", "cmd");
    }

    @Test
    @DisplayName("runner 는 wsl.exe --list --quiet 를 생성자의 시간 초과로 받는다")
    void runnerReceivesCommandAndTimeout() {
        FakeCommandRunner runner = new FakeCommandRunner(two());
        new WindowsShellCatalog(runner, ENV, Duration.ofMillis(1234)).shells();
        assertThat(runner.commands()).containsExactly(List.of("C:\\Windows\\System32\\wsl.exe", "--list", "--quiet"));
        assertThat(runner.timeouts()).containsExactly(Duration.ofMillis(1234));
    }

    @Test
    @DisplayName("ComSpec 이 없으면 SystemRoot\\System32\\cmd.exe 를 쓴다")
    void comSpecFallback() {
        Function<String, Optional<String>> env =
                key -> key.equals("SystemRoot") ? Optional.of("D:\\Win") : Optional.empty();
        List<ShellProfile> all = new WindowsShellCatalog(
                        new FakeCommandRunner(new CommandOutcome.NotStarted("x")), env, TIMEOUT)
                .shells();
        assertThat(all.get(1).launch().executable()).isEqualTo("D:\\Win\\System32\\cmd.exe");
        assertThat(all.get(0).launch().executable())
                .isEqualTo("D:\\Win\\System32\\WindowsPowerShell\\v1.0\\powershell.exe");
    }

    @Test
    @DisplayName("SystemRoot 가 없으면 C:\\Windows 를 쓴다")
    void systemRootFallback() {
        FakeCommandRunner runner = new FakeCommandRunner(new CommandOutcome.NotStarted("x"));
        List<ShellProfile> all = new WindowsShellCatalog(runner, key -> Optional.empty(), TIMEOUT).shells();
        assertThat(runner.commands().get(0).get(0)).isEqualTo("C:\\Windows\\System32\\wsl.exe");
        assertThat(all.get(1).launch().executable()).isEqualTo("C:\\Windows\\System32\\cmd.exe");
    }

    @Test
    @DisplayName("결과는 수정할 수 없고 id 가 겹치지 않는다")
    void immutableAndUnique() {
        List<ShellProfile> all = shells(two());
        assertThatThrownBy(() -> all.add(all.get(0))).isInstanceOf(UnsupportedOperationException.class);
        assertThat(ids(all)).doesNotHaveDuplicates();
    }

    private static List<String> executables(List<ShellProfile> profiles) {
        return profiles.stream().map(p -> p.launch().executable()).toList();
    }

    @Test
    @DisplayName("ComSpec 이 공백이면 SystemRoot\\System32\\cmd.exe 를 쓴다")
    void blankComSpecFallsBack() {
        Function<String, Optional<String>> env = key -> Optional.ofNullable(
                Map.of("SystemRoot", "C:\\Windows", "ComSpec", "   ").get(key));
        List<ShellProfile> all = new WindowsShellCatalog(
                        new FakeCommandRunner(new CommandOutcome.NotStarted("x")), env, TIMEOUT)
                .shells();
        assertThat(all.get(1).launch().executable()).isEqualTo("C:\\Windows\\System32\\cmd.exe");
    }

    @Test
    @DisplayName("ComSpec 이 상대 경로면 SystemRoot\\System32\\cmd.exe 를 쓴다")
    void relativeComSpecFallsBack() {
        Function<String, Optional<String>> env = key -> Optional.ofNullable(
                Map.of("SystemRoot", "C:\\Windows", "ComSpec", "cmd.exe").get(key));
        List<ShellProfile> all = new WindowsShellCatalog(
                        new FakeCommandRunner(new CommandOutcome.NotStarted("x")), env, TIMEOUT)
                .shells();
        assertThat(all.get(1).launch().executable()).isEqualTo("C:\\Windows\\System32\\cmd.exe");
    }

    @Test
    @DisplayName("SystemRoot 가 상대 경로면 C:\\Windows 를 쓴다")
    void relativeSystemRootFallsBack() {
        // given
        FakeCommandRunner runner = new FakeCommandRunner(new CommandOutcome.NotStarted("x"));
        Function<String, Optional<String>> env =
                key -> key.equals("SystemRoot") ? Optional.of("Windows") : Optional.empty();
        // when
        List<ShellProfile> all = new WindowsShellCatalog(runner, env, TIMEOUT).shells();
        // then
        assertThat(runner.commands().get(0).get(0)).isEqualTo("C:\\Windows\\System32\\wsl.exe");
        assertThat(executables(all))
                .containsExactly(
                        "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
                        "C:\\Windows\\System32\\cmd.exe");
    }

    @Test
    @DisplayName("SystemRoot 끝의 역슬래시 때문에 경로에 \\\\ 가 생기지 않는다")
    void trailingBackslashSystemRoot() {
        // given
        FakeCommandRunner runner = new FakeCommandRunner(two());
        Function<String, Optional<String>> env =
                key -> key.equals("SystemRoot") ? Optional.of("C:\\Windows\\") : Optional.empty();
        // when
        List<ShellProfile> all = new WindowsShellCatalog(runner, env, TIMEOUT).shells();
        // then
        assertThat(runner.commands().get(0).get(0)).isEqualTo("C:\\Windows\\System32\\wsl.exe");
        assertThat(executables(all)).noneMatch(e -> e.contains("\\\\"));
    }

    @Test
    @DisplayName("종료 코드 0 이어도 안내 문구만 나오면 WSL 항목이 없다")
    void guidanceWithExitZeroDropsWsl() {
        CommandOutcome guidance = new CommandOutcome.Exited(
                0,
                "Windows Subsystem for Linux has no installed distributions.\r\n".getBytes(StandardCharsets.UTF_16LE));
        assertThat(ids(shells(guidance))).containsExactly("powershell", "cmd");
    }

    @Test
    @DisplayName("docker-desktop 처럼 셸 용도가 아닌 배포판은 탐지에서 뺀다")
    void excludesNonShellDistros() {
        // given
        CommandOutcome outcome = new CommandOutcome.Exited(
                0,
                "docker-desktop\r\nUbuntu\r\ndocker-desktop-data\r\nRancher-Desktop\r\nrancher-desktop-data\r\n"
                        .getBytes(StandardCharsets.UTF_16LE));
        // when
        List<ShellProfile> profiles = shells(outcome);
        // then
        assertThat(ids(profiles)).containsExactly("wsl:Ubuntu", "powershell", "cmd");
    }
}
