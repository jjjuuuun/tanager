package tanager.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.history.WslMount;
import tanager.domain.history.WslMounts;

/** 환경의 경로 → 이 앱이 여는 Windows 경로(순수, #205). 파일시스템을 보지 않는다. */
class WindowsHostPathsTest {
    private static final ShellEnvironment WINDOWS = new ShellEnvironment.Windows();
    private static final ShellEnvironment UBUNTU = new ShellEnvironment.Wsl("Ubuntu");
    // wsl.conf 의 [automount] root = / 인 배포판
    private static final ShellEnvironment ROOTED = new ShellEnvironment.Wsl("Rooted");
    private static final WslMounts MOUNTS = WslMounts.DEFAULT.with("Rooted", new WslMount("/"));

    static Stream<Arguments> conversions() {
        return Stream.of(
                Arguments.of(UBUNTU, "/home/me/proj", Optional.of("\\\\wsl.localhost\\Ubuntu\\home\\me\\proj")),
                Arguments.of(UBUNTU, "/mnt/c/work/infra", Optional.of("C:\\work\\infra")),
                Arguments.of(UBUNTU, "/mnt/d", Optional.of("D:\\")),
                Arguments.of(UBUNTU, "/", Optional.of("\\\\wsl.localhost\\Ubuntu\\")),
                Arguments.of(ROOTED, "/c/work", Optional.of("C:\\work")),
                Arguments.of(ROOTED, "/mnt/c/work", Optional.of("\\\\wsl.localhost\\Rooted\\mnt\\c\\work")),
                Arguments.of(WINDOWS, "C:\\Users\\me", Optional.of("C:\\Users\\me")),
                Arguments.of(WINDOWS, "C:\\Users\\홍길동\\내 문서", Optional.of("C:\\Users\\홍길동\\내 문서")),
                Arguments.of(WINDOWS, "\\\\server\\share\\a", Optional.of("\\\\server\\share\\a")),
                Arguments.of(
                        UBUNTU, "/home/me/한글 폴더/a b", Optional.of("\\\\wsl.localhost\\Ubuntu\\home\\me\\한글 폴더\\a b")),
                // 리눅스는 허용하지만 Windows 이름에 쓸 수 없는 글자 · 끝 점 · 끝 공백은 다른 파일을 가리키거나 열리지 않는다
                Arguments.of(UBUNTU, "/home/me/a:b", Optional.empty()),
                Arguments.of(UBUNTU, "/home/me/what?", Optional.empty()),
                Arguments.of(UBUNTU, "/home/me/<x>|*", Optional.empty()),
                Arguments.of(UBUNTU, "/home/me/dir.", Optional.empty()),
                Arguments.of(UBUNTU, "/home/me/dir ", Optional.empty()));
    }

    @ParameterizedTest(name = "{0} {1} → {2}")
    @MethodSource("conversions")
    @DisplayName("WSL 의 드라이브 아래는 드라이브 경로, 그 밖은 \\\\wsl.localhost, Windows 는 글자 그대로다")
    void convertsToHostPath(ShellEnvironment env, String raw, Optional<String> expected) {
        // given
        WorkspacePath path = WorkspacePath.of(env, raw).value().orElseThrow();

        // when
        Optional<String> host = WindowsHostPaths.hostPath(path, MOUNTS);

        // then
        assertThat(host).isEqualTo(expected);
    }
}
