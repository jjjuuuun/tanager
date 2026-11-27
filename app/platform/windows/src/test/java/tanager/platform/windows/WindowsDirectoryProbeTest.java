package tanager.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.out.DirectoryProbe;
import tanager.application.port.out.DirectoryProbeContract;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.history.WslMount;

/** WSL · Windows 경로를 Windows 파일 경로로 바꿔 확인한다(#53). 파일시스템과 실행기는 Fake 다. */
class WindowsDirectoryProbeTest extends DirectoryProbeContract {
    private static final Executor DIRECT = Runnable::run;
    // 작업을 실행하지 않는다 — 답이 끝내 오지 않는 파일시스템(깨어나지 않는 VM)과 같다
    private static final Executor NEVER = task -> {};
    private static final Duration ONE_SECOND = Duration.ofSeconds(1);
    private static final String UBUNTU_UNC = "\\\\wsl.localhost\\Ubuntu";
    private static final String WSL_CONF = UBUNTU_UNC + "\\etc\\wsl.conf";

    private final FakeWindowsFiles files = new FakeWindowsFiles()
            .directory("C:\\Users", Optional.of(true))
            .directory(UBUNTU_UNC + "\\home\\me", Optional.of(true))
            .directory(UBUNTU_UNC + "\\home\\me\\gone", Optional.of(false));
    private final WslMountRoots mounts = new WslMountRoots();

    private static WorkspacePath ubuntu(String raw) {
        return WorkspacePath.of(new ShellEnvironment.Wsl("Ubuntu"), raw).value().orElseThrow();
    }

    private static WorkspacePath windows(String raw) {
        return WorkspacePath.of(new ShellEnvironment.Windows(), raw).value().orElseThrow();
    }

    private WindowsDirectoryProbe probeWith(Executor executor) {
        return new WindowsDirectoryProbe(files, mounts, executor);
    }

    @Override
    protected DirectoryProbe probe() {
        return probeWith(DIRECT);
    }

    @Override
    protected WorkspacePath existingDirectory() {
        return ubuntu("/home/me");
    }

    @Override
    protected WorkspacePath missingPath() {
        return ubuntu("/home/me/gone");
    }

    @Test
    @DisplayName("WSL 리눅스 경로가 없으면 \\\\wsl.localhost\\<배포판>\\… 로 확인해 Optional.of(false) 다")
    void missingWslPathIsFalseThroughUnc() {
        // when
        var answer = probeWith(DIRECT).isDirectory(ubuntu("/home/me/gone"), ONE_SECOND);

        // then
        assertThat(answer).contains(false);
        assertThat(files.probed()).containsExactly(UBUNTU_UNC + "\\home\\me\\gone");
    }

    @Test
    @DisplayName("/mnt/c/Users 는 C:\\Users 로 확인한다 — \\\\wsl.localhost 를 거치지 않는다")
    void mountedDrivePathSkipsWsl() {
        // when
        var answer = probeWith(DIRECT).isDirectory(ubuntu("/mnt/c/Users"), ONE_SECOND);

        // then
        assertThat(answer).contains(true);
        assertThat(files.probed()).containsExactly("C:\\Users");
        assertThat(files.read()).isEmpty();
    }

    @Test
    @DisplayName("Windows 경로는 그대로 확인한다")
    void windowsPathAsIs() {
        // when
        var answer = probeWith(DIRECT).isDirectory(windows("C:\\Users"), ONE_SECOND);

        // then
        assertThat(answer).contains(true);
        assertThat(files.probed()).containsExactly("C:\\Users");
    }

    @Test
    @DisplayName("WSL 루트 / 는 \\\\wsl.localhost\\<배포판>\\ 다")
    void wslRootIsDistroShare() {
        // given
        files.directory(UBUNTU_UNC + "\\", Optional.of(true));

        // when
        var answer = probeWith(DIRECT).isDirectory(ubuntu("/"), ONE_SECOND);

        // then
        assertThat(answer).contains(true);
        assertThat(files.probed()).containsExactly(UBUNTU_UNC + "\\");
    }

    @Test
    @DisplayName("시간 제한 안에 답이 없으면 모름(empty)이다")
    void timeoutIsUnknown() {
        // when
        var answer = probeWith(NEVER).isDirectory(ubuntu("/home/me"), Duration.ofMillis(1));

        // then
        assertThat(answer).isEmpty();
    }

    @Test
    @DisplayName("접근할 수 없으면(파일시스템이 모름) empty 다")
    void inaccessibleIsUnknown() {
        // given
        files.directory(UBUNTU_UNC + "\\home\\me", Optional.empty());

        // when
        var answer = probeWith(DIRECT).isDirectory(ubuntu("/home/me"), ONE_SECOND);

        // then
        assertThat(answer).isEmpty();
    }

    @Test
    @DisplayName("확인 중 예외가 나거나 실행기가 작업을 거절하면 예외 없이 empty 다")
    void failuresAreUnknown() {
        // given
        Executor rejecting = task -> {
            throw new RejectedExecutionException("닫힘");
        };
        FakeWindowsFiles broken = new FakeWindowsFiles().failWith(new IllegalStateException("버그"));

        // when
        var rejected = probeWith(rejecting).isDirectory(ubuntu("/home/me"), ONE_SECOND);
        var thrown = new WindowsDirectoryProbe(broken, mounts, DIRECT).isDirectory(ubuntu("/home/me"), ONE_SECOND);

        // then
        assertThat(rejected).isEmpty();
        assertThat(thrown).isEmpty();
    }

    @Test
    @DisplayName("기다리는 중 인터럽트되면 empty 를 돌려주고 인터럽트 표시를 되살린다")
    void interruptIsUnknownAndRestored() {
        // given
        Thread.currentThread().interrupt();

        // when
        var answer = probeWith(NEVER).isDirectory(ubuntu("/home/me"), ONE_SECOND);

        // then
        assertThat(Thread.interrupted()).isTrue();
        assertThat(answer).isEmpty();
    }

    @Test
    @DisplayName("\\\\wsl.localhost 로 답을 받으면 그 배포판의 wsl.conf 에서 마운트 루트를 한 번 읽어 둔다")
    void learnsMountRootOnceAfterReachingDistro() {
        // given
        files.text(WSL_CONF, Optional.of("[automount]\nroot = /win/\n"));
        WindowsDirectoryProbe probe = probeWith(DIRECT);

        // when
        probe.isDirectory(ubuntu("/home/me"), ONE_SECOND);
        probe.isDirectory(ubuntu("/home/me/gone"), ONE_SECOND);

        // then
        assertThat(files.read()).containsExactly(WSL_CONF);
        assertThat(mounts.snapshot().of("Ubuntu")).isEqualTo(new WslMount("/win/"));
    }

    @Test
    @DisplayName("마운트 루트를 알게 되면 /win/c/x 를 C:\\x 로 확인한다")
    void usesLearnedMountRoot() {
        // given
        files.text(WSL_CONF, Optional.of("[automount]\nroot = /win/\n")).directory("C:\\x", Optional.of(true));
        WindowsDirectoryProbe probe = probeWith(DIRECT);
        probe.isDirectory(ubuntu("/home/me"), ONE_SECOND);

        // when
        var answer = probe.isDirectory(ubuntu("/win/c/x"), ONE_SECOND);

        // then
        assertThat(answer).contains(true);
        assertThat(files.probed()).endsWith("C:\\x");
    }

    @Test
    @DisplayName("wsl.conf 가 없으면 기본 /mnt/ 로 알아 둔다")
    void missingWslConfMeansDefault() {
        // when
        probeWith(DIRECT).isDirectory(ubuntu("/home/me"), ONE_SECOND);

        // then
        assertThat(mounts.knows("Ubuntu")).isTrue();
        assertThat(mounts.snapshot().of("Ubuntu")).isEqualTo(WslMount.DEFAULT);
    }

    @Test
    @DisplayName("배포판이 답하지 않았거나 wsl.conf 를 읽지 못하면 마운트 루트를 정하지 않는다(다음에 다시 읽는다)")
    void doesNotLearnWithoutAnswer() {
        // given
        files.directory(UBUNTU_UNC + "\\home\\me", Optional.empty());
        files.text(WSL_CONF, Optional.empty());
        WindowsDirectoryProbe probe = probeWith(DIRECT);

        // when
        probe.isDirectory(ubuntu("/home/me"), ONE_SECOND);
        probe.isDirectory(ubuntu("/home/me/gone"), ONE_SECOND);

        // then
        assertThat(files.read()).containsExactly(WSL_CONF);
        assertThat(mounts.knows("Ubuntu")).isFalse();
    }
}
