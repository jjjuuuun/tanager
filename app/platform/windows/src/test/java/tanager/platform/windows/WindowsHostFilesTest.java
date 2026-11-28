package tanager.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tanager.application.port.out.HomeError;
import tanager.application.port.out.HostFiles;
import tanager.application.port.out.HostFilesContract;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.history.WslMount;

/** 환경의 홈과 호스트 경로(#205). 명령 실행기는 Fake 다 — wsl.exe 를 실행하지 않는다. */
class WindowsHostFilesTest extends HostFilesContract {
    private static final ShellEnvironment WINDOWS = new ShellEnvironment.Windows();
    private static final ShellEnvironment UBUNTU = new ShellEnvironment.Wsl("Ubuntu");
    private static final List<String> UBUNTU_HOME_COMMAND =
            List.of("wsl.exe", "-d", "Ubuntu", "--exec", "printenv", "HOME");

    private final WslMountRoots mounts = new WslMountRoots();

    private static CommandOutcome printed(String stdout) {
        return new CommandOutcome.Exited(0, stdout.getBytes(StandardCharsets.UTF_8));
    }

    private static WorkspacePath ubuntu(String raw) {
        return WorkspacePath.of(UBUNTU, raw).value().orElseThrow();
    }

    private WindowsHostFiles filesWith(CommandRunner runner) {
        return new WindowsHostFiles(mounts, runner, "C:\\Users\\me");
    }

    @Override
    protected HostFiles hostFiles() {
        return filesWith(new FakeCommandRunner(new CommandOutcome.Exited(1, new byte[0])));
    }

    @Override
    protected ShellEnvironment unknownEnvironment() {
        return new ShellEnvironment.Wsl("NoSuchDistro");
    }

    @Test
    @DisplayName("WSL 홈은 WSL_UTF8=1 · 5초 제한으로 한 번 묻고, 끝 줄바꿈 · \\r 을 버려 기억한다")
    void wslHomeIsAskedOnceAndRemembered() {
        // given
        FakeCommandRunner runner = new FakeCommandRunner(printed("/home/me\r\n"));
        WindowsHostFiles files = filesWith(runner);

        // when
        var first = files.home(UBUNTU);
        var second = files.home(UBUNTU);

        // then
        assertThat(first.value()).contains(ubuntu("/home/me"));
        assertThat(second.value()).contains(ubuntu("/home/me"));
        assertThat(runner.commands()).containsExactly(UBUNTU_HOME_COMMAND);
        assertThat(runner.envs()).containsExactly(Map.of("WSL_UTF8", "1"));
        assertThat(runner.timeouts()).containsExactly(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("배포판마다 따로 묻는다")
    void eachDistroIsAskedSeparately() {
        // given
        ScriptedCommandRunner runner = new ScriptedCommandRunner()
                .on(UBUNTU_HOME_COMMAND, printed("/home/me\n"))
                .on(List.of("wsl.exe", "-d", "Debian", "--exec", "printenv", "HOME"), printed("/root\n"));
        WindowsHostFiles files = filesWith(runner);

        // when
        var ubuntuHome = files.home(UBUNTU);
        var debianHome = files.home(new ShellEnvironment.Wsl("Debian"));

        // then
        assertThat(ubuntuHome.value()).contains(ubuntu("/home/me"));
        assertThat(debianHome.value().map(WorkspacePath::path)).contains("/root");
        assertThat(runner.commands()).hasSize(2);
    }

    @Test
    @DisplayName("한글 홈은 UTF-8 로 읽는다")
    void koreanHomeIsDecodedAsUtf8() {
        // given
        WindowsHostFiles files = filesWith(new FakeCommandRunner(printed("/home/홍길동\n")));

        // when
        var home = files.home(UBUNTU);

        // then
        assertThat(home.value()).contains(ubuntu("/home/홍길동"));
    }

    @Test
    @DisplayName("5초를 넘기면 TimedOut 이고 기억하지 않아 다음 호출이 다시 묻는다")
    void timeoutIsNotRemembered() {
        // given
        FakeCommandRunner runner = new FakeCommandRunner(new CommandOutcome.TimedOut(Duration.ofSeconds(5)));
        WindowsHostFiles files = filesWith(runner);

        // when
        var first = files.home(UBUNTU);
        var second = files.home(UBUNTU);

        // then
        assertThat(first.error()).contains(new HomeError.TimedOut(UBUNTU));
        assertThat(second.error()).contains(new HomeError.TimedOut(UBUNTU));
        assertThat(runner.commands()).hasSize(2);
    }

    static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of(printed("relative\n"), new HomeError.Invalid(UBUNTU)),
                Arguments.of(printed(""), new HomeError.Invalid(UBUNTU)),
                Arguments.of(printed("\r\n"), new HomeError.Invalid(UBUNTU)),
                Arguments.of(printed("/home/a\n/home/b\n"), new HomeError.Invalid(UBUNTU)),
                Arguments.of(new CommandOutcome.Exited(255, new byte[0]), new HomeError.NotRunnable(UBUNTU)),
                Arguments.of(new CommandOutcome.NotStarted("wsl.exe 없음"), new HomeError.NotRunnable(UBUNTU)));
    }

    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("failures")
    @DisplayName("절대 경로가 아닌 답은 Invalid, 실행 실패 · 0 이 아닌 종료는 NotRunnable 이고 기억하지 않는다")
    void failuresAreErrorsAndNotRemembered(CommandOutcome outcome, HomeError expected) {
        // given
        FakeCommandRunner runner = new FakeCommandRunner(outcome);
        WindowsHostFiles files = filesWith(runner);

        // when
        var first = files.home(UBUNTU);
        files.home(UBUNTU);

        // then
        assertThat(first.error()).contains(expected);
        assertThat(runner.commands()).hasSize(2);
    }

    @Test
    @DisplayName("명령줄에 넣을 수 없는 배포판 이름은 실행하지 않고 NotRunnable 이다")
    void unsafeDistroNameIsNotRun() {
        // given
        FakeCommandRunner runner = new FakeCommandRunner(printed("/home/me\n"));
        ShellEnvironment odd = new ShellEnvironment.Wsl("Ubuntu --exec rm");

        // when
        var home = filesWith(runner).home(odd);

        // then
        assertThat(home.error()).contains(new HomeError.NotRunnable(odd));
        assertThat(runner.commands()).isEmpty();
    }

    @Test
    @DisplayName("Windows 홈은 주입한 USERPROFILE 이고 명령을 실행하지 않는다")
    void windowsHomeIsUserProfile() {
        // given
        FakeCommandRunner runner = new FakeCommandRunner(printed("/home/me\n"));

        // when
        var home = filesWith(runner).home(WINDOWS);

        // then
        assertThat(home.value().map(WorkspacePath::path)).contains("C:\\Users\\me");
        assertThat(runner.commands()).isEmpty();
    }

    @Test
    @DisplayName("Windows 홈이 절대 경로가 아니면 Invalid 다")
    void relativeWindowsHomeIsInvalid() {
        // given
        WindowsHostFiles files = new WindowsHostFiles(mounts, new FakeCommandRunner(printed("")), "/home/linux-user");

        // when
        var home = files.home(WINDOWS);

        // then
        assertThat(home.error()).contains(new HomeError.Invalid(WINDOWS));
    }

    @Test
    @DisplayName("hostPath 는 명령을 실행하지 않고 지금 아는 배포판별 마운트 루트를 쓴다")
    void hostPathUsesCurrentMountRootsWithoutRunningCommands() {
        // given
        FakeCommandRunner runner = new FakeCommandRunner(printed("/home/me\n"));
        WindowsHostFiles files = filesWith(runner);
        var before = files.hostPath(ubuntu("/c/work"));

        // when
        mounts.remember("Ubuntu", new WslMount("/"));
        var after = files.hostPath(ubuntu("/c/work"));

        // then
        assertThat(before).contains(Path.of("\\\\wsl.localhost\\Ubuntu\\c\\work"));
        assertThat(after).contains(Path.of("C:\\work"));
        assertThat(runner.commands()).isEmpty();
    }
}
