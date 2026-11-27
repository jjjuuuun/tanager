package tanager.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.out.ProbeError;
import tanager.domain.history.PathTranslator;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.history.WslMount;
import tanager.domain.monitor.DiskUsage;
import tanager.domain.monitor.HostUsage;
import tanager.domain.monitor.Usage;
import tanager.domain.monitor.WslUsage;

/** Windows 리소스 수집기 — 디스크 루트 결정과 wsl.exe 호출(FR-MON-01 · 02, #187). */
class WindowsResourceProbeTest {
    private static final Usage HALF = new Usage(50, 100);
    private static final HostUsage HOST = new HostUsage(0.2, 8, HALF);
    private static final Duration ONE_SECOND = Duration.ofSeconds(1);

    private final List<String> diskRoots = new ArrayList<>();

    private WindowsResourceProbe probe(CommandRunner runner) {
        return new WindowsResourceProbe(
                () -> HOST,
                root -> {
                    diskRoots.add(root);
                    return Optional.of(HALF);
                },
                runner,
                (from, to) -> PathTranslator.translate(from, to, WslMount.DEFAULT));
    }

    private static WorkspacePath path(ShellEnvironment env, String raw) {
        return WorkspacePath.of(env, raw).value().orElseThrow();
    }

    private static CommandOutcome exited(String stdout) {
        return new CommandOutcome.Exited(0, stdout.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("host() 는 주입한 호스트 표본을 돌려준다")
    void host() {
        assertThat(probe(new FakeCommandRunner(exited(""))).host()).isEqualTo(HOST);
    }

    @Test
    @DisplayName("Windows cwd 는 그 드라이브 루트로 잰다")
    void windowsDrive() {
        // when
        Optional<DiskUsage> disk =
                probe(new FakeCommandRunner(exited(""))).disk(path(new ShellEnvironment.Windows(), "c:\\work\\x"));

        // then
        assertThat(disk).contains(new DiskUsage("C:\\", HALF));
        assertThat(diskRoots).containsExactly("C:\\");
    }

    @Test
    @DisplayName("WSL cwd /mnt/d/x 는 D:\\ 로 잰다")
    void wslMountedDrive() {
        // when
        Optional<DiskUsage> disk =
                probe(new FakeCommandRunner(exited(""))).disk(path(new ShellEnvironment.Wsl("Ubuntu"), "/mnt/d/x"));

        // then
        assertThat(disk).contains(new DiskUsage("D:\\", HALF));
    }

    @Test
    @DisplayName("WSL 리눅스 쪽 경로와 UNC 경로는 재지 않는다(배포판 / 는 wsl() 이, 네트워크 공유는 막힐 수 있어 뺀다)")
    void linuxSideAndUncAreEmpty() {
        // given
        WindowsResourceProbe probe = probe(new FakeCommandRunner(exited("")));

        // then
        assertThat(probe.disk(path(new ShellEnvironment.Wsl("Ubuntu"), "/home/u")))
                .isEmpty();
        assertThat(probe.disk(path(new ShellEnvironment.Windows(), "\\\\server\\share\\x")))
                .isEmpty();
        assertThat(diskRoots).isEmpty();
    }

    @Test
    @DisplayName("wsl() 은 로그인 셸을 거치지 않고(--exec) meminfo 와 C 로캘 df 를 한 번에 읽고 WSL_UTF8=1 을 준다")
    void wslCommand() {
        // given
        FakeCommandRunner runner = new FakeCommandRunner(exited(MeminfoParserTest.fixture("wsl-ubuntu-24.04.txt")));

        // when
        var result = probe(runner).wsl("Ubuntu-24.04", ONE_SECOND);

        // then
        assertThat(runner.commands())
                .containsExactly(List.of(
                        "wsl.exe", "-d", "Ubuntu-24.04", "--exec", "sh", "-c", "cat /proc/meminfo; LC_ALL=C df -kP /"));
        assertThat(runner.envs()).containsExactly(Map.of("WSL_UTF8", "1"));
        assertThat(runner.timeouts()).containsExactly(ONE_SECOND);
        assertThat(result.value())
                .contains(new WslUsage(
                        "Ubuntu-24.04",
                        new Usage((11216076L - 2578028L) * 1024, 11216076L * 1024),
                        new Usage(134125748L * 1024, 1055762868L * 1024)));
    }

    @Test
    @DisplayName("시간 초과면 TimedOut, 띄우지 못했거나 0 이 아닌 코드면 Failed, 출력이 모자라면 Unreadable 이다")
    void wslFailures() {
        assertThat(probe(new FakeCommandRunner(new CommandOutcome.TimedOut(ONE_SECOND)))
                        .wsl("Ubuntu", ONE_SECOND)
                        .error())
                .contains(new ProbeError.TimedOut(ONE_SECOND));
        assertThat(probe(new FakeCommandRunner(new CommandOutcome.NotStarted("없음")))
                        .wsl("Ubuntu", ONE_SECOND)
                        .error())
                .containsInstanceOf(ProbeError.Failed.class);
        assertThat(probe(new FakeCommandRunner(new CommandOutcome.Exited(1, new byte[0])))
                        .wsl("Ubuntu", ONE_SECOND)
                        .error())
                .containsInstanceOf(ProbeError.Failed.class);
        assertThat(probe(new FakeCommandRunner(exited("MemTotal: 10 kB\nMemAvailable: 5 kB\n")))
                        .wsl("Ubuntu", ONE_SECOND)
                        .error())
                .containsInstanceOf(ProbeError.Unreadable.class);
    }

    @Test
    @DisplayName("배포판 이름에 공백 · 따옴표 · 제어 문자가 있으면 wsl.exe 를 부르지 않는다(인자 주입 방지)")
    void rejectsUnsafeDistroName() {
        // given
        FakeCommandRunner runner = new FakeCommandRunner(exited(""));

        // when
        var spaced = probe(runner).wsl("Ubuntu --exec rm", ONE_SECOND);
        var quoted = probe(runner).wsl("Ubu\"ntu", ONE_SECOND);

        // then
        assertThat(spaced.error()).containsInstanceOf(ProbeError.Failed.class);
        assertThat(quoted.error()).containsInstanceOf(ProbeError.Failed.class);
        assertThat(runner.commands()).isEmpty();
    }
}
