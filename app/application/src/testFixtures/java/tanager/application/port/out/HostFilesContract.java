package tanager.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;

/** {@link HostFiles} 계약(#205). Fake 와 플랫폼 구현이 같이 상속한다. 하위 클래스는 외부 명령을 실행하지 않는 결정적인 구성을 돌려준다. */
@Tag("contract")
public abstract class HostFilesContract {

    /** 검사할 구현. */
    protected abstract HostFiles hostFiles();

    /** 이 구현이 홈을 모르는 환경(없는 배포판 등). */
    protected abstract ShellEnvironment unknownEnvironment();

    @Test
    @DisplayName("Windows 경로는 글자 그대로의 호스트 경로다")
    void windowsPathIsUnchanged() {
        // given
        WorkspacePath path = WorkspacePath.of(new ShellEnvironment.Windows(), "C:\\Users\\me\\작업 폴더")
                .value()
                .orElseThrow();

        // when
        var host = hostFiles().hostPath(path);

        // then
        assertThat(host).contains(Path.of("C:\\Users\\me\\작업 폴더"));
    }

    @Test
    @DisplayName("홈을 모르는 환경은 예외 없이 그 환경을 담은 오류다")
    void unknownEnvironmentIsErrorWithoutThrowing() {
        // given
        HostFiles files = hostFiles();
        ShellEnvironment env = unknownEnvironment();

        // when
        var home = files.home(env);

        // then
        assertThat(home.error())
                .hasValueSatisfying(error -> assertThat(error.env()).isEqualTo(env));
    }

    @Test
    @DisplayName("홈 · 호스트 경로는 어느 환경에서도 예외를 던지지 않는다")
    void neverThrows() {
        // given
        HostFiles files = hostFiles();
        WorkspacePath wsl = WorkspacePath.of(new ShellEnvironment.Wsl("Ubuntu"), "/home/me")
                .value()
                .orElseThrow();

        // when / then
        assertThatCode(() -> {
                    files.hostPath(wsl);
                    files.home(new ShellEnvironment.Windows());
                })
                .doesNotThrowAnyException();
    }
}
