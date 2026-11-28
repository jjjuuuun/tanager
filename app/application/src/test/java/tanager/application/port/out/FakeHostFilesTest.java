package tanager.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;

/** Fake 가 {@link HostFiles} 계약을 지키는지, 정해 둔 답과 호출 기록이 맞는지 확인한다(#205). */
class FakeHostFilesTest extends HostFilesContract {
    private static final ShellEnvironment UBUNTU = new ShellEnvironment.Wsl("Ubuntu");

    private static WorkspacePath ubuntu(String raw) {
        return WorkspacePath.of(UBUNTU, raw).value().orElseThrow();
    }

    @Override
    protected HostFiles hostFiles() {
        return new FakeHostFiles();
    }

    @Override
    protected ShellEnvironment unknownEnvironment() {
        return new ShellEnvironment.Wsl("NoSuchDistro");
    }

    @Test
    @DisplayName("withHome 으로 정한 홈을 답하고 받은 환경을 순서대로 기록한다")
    void answersConfiguredHomeAndRecordsRequests() {
        // given
        FakeHostFiles files = new FakeHostFiles().withHome(UBUNTU, ubuntu("/home/me"));

        // when
        var home = files.home(UBUNTU);
        files.home(new ShellEnvironment.Windows());

        // then
        assertThat(home.value()).contains(ubuntu("/home/me"));
        assertThat(files.homeRequests()).containsExactly(UBUNTU, new ShellEnvironment.Windows());
    }

    @Test
    @DisplayName("withHomeError 로 정한 오류를 답한다")
    void answersConfiguredError() {
        // given
        FakeHostFiles files = new FakeHostFiles().withHomeError(new HomeError.TimedOut(UBUNTU));

        // when
        var home = files.home(UBUNTU);

        // then
        assertThat(home.error()).contains(new HomeError.TimedOut(UBUNTU));
    }

    @Test
    @DisplayName("기본 hostPath 는 드라이브 아래를 C:\\… 로, 그 밖의 WSL 경로를 \\\\wsl.localhost\\… 로 바꾸고 withHostPath 로 덮는다")
    void defaultHostPathFollowsWindowsRuleAndCanBeOverridden() {
        // given
        FakeHostFiles files = new FakeHostFiles().withHostPath(ubuntu("/opt/x"), Optional.empty());

        // when / then
        assertThat(files.hostPath(ubuntu("/mnt/c/work"))).contains(Path.of("C:\\work"));
        assertThat(files.hostPath(ubuntu("/home/me"))).contains(Path.of("\\\\wsl.localhost\\Ubuntu\\home\\me"));
        assertThat(files.hostPath(ubuntu("/opt/x"))).isEmpty();
    }
}
