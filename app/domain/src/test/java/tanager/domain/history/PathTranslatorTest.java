package tanager.domain.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** WSL ↔ Windows 경로 변환(#90, SDD §10.3). */
class PathTranslatorTest {
    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();
    private static final ShellEnvironment U = new ShellEnvironment.Wsl("U");
    private static final ShellEnvironment V = new ShellEnvironment.Wsl("V");

    private static WorkspacePath path(ShellEnvironment env, String raw) {
        return WorkspacePath.of(env, raw).value().orElseThrow();
    }

    private static Optional<String> translate(ShellEnvironment from, String raw, ShellEnvironment to) {
        return PathTranslator.translate(path(from, raw), to, WslMount.DEFAULT).map(WorkspacePath::path);
    }

    @Test
    @DisplayName("WSL /mnt/<드라이브>/… 는 Windows <드라이브>:\\… 다")
    void wslMountToWindows() {
        assertThat(translate(U, "/mnt/c/work/infra", WIN)).contains("C:\\work\\infra");
        assertThat(translate(U, "/mnt/d", WIN)).contains("D:\\");
    }

    @Test
    @DisplayName("Windows 드라이브 경로는 아무 WSL 의 /mnt/<드라이브> 다")
    void windowsDriveToWsl() {
        assertThat(translate(WIN, "C:\\work", U)).contains("/mnt/c/work");
        assertThat(translate(WIN, "D:\\", V)).contains("/mnt/d");
    }

    @Test
    @DisplayName("WSL 홈 같은 리눅스 경로는 Windows 셸의 cwd 로 쓰지 않는다 — empty")
    void wslLinuxPathHasNoWindowsCwd() {
        assertThat(translate(U, "/home/me/proj", WIN)).isEmpty();
    }

    @Test
    @DisplayName("\\\\wsl.localhost\\U\\… 와 \\\\wsl$\\U\\… 는 그 배포판에서만 /… 다")
    void wslUncToThatDistroOnly() {
        assertThat(translate(WIN, "\\\\wsl.localhost\\U\\home\\me", U)).contains("/home/me");
        assertThat(translate(WIN, "\\\\wsl$\\U\\home\\me", U)).contains("/home/me");
        assertThat(translate(WIN, "\\\\wsl.localhost\\U", U)).contains("/");
        assertThat(translate(WIN, "\\\\wsl.localhost\\U\\home\\me", V)).isEmpty();
        assertThat(translate(WIN, "\\\\server\\share\\x", U)).isEmpty();
    }

    @Test
    @DisplayName("드라이브 문자가 아닌 /mnt 아래 · /mnt 자체는 Windows 경로가 없다")
    void notADriveMount() {
        assertThat(translate(U, "/mnt/cdrom/x", WIN)).isEmpty();
        assertThat(translate(U, "/mnt", WIN)).isEmpty();
    }

    @Test
    @DisplayName("마운트 루트가 / 이면 /c/work 가 C:\\work 다")
    void customMountRoot() {
        WslMount root = new WslMount("/");
        assertThat(PathTranslator.translate(path(U, "/c/work"), WIN, root).map(WorkspacePath::path))
                .contains("C:\\work");
        assertThat(PathTranslator.translate(path(WIN, "C:\\work"), U, root).map(WorkspacePath::path))
                .contains("/c/work");
    }

    @Test
    @DisplayName("같은 환경은 그대로, 다른 WSL 배포판은 드라이브 아래만 같은 경로다")
    void sameEnvironmentAndOtherDistro() {
        assertThat(translate(WIN, "C:\\x", WIN)).contains("C:\\x");
        assertThat(translate(U, "/home/me", U)).contains("/home/me");
        assertThat(translate(U, "/mnt/c/x", V)).contains("/mnt/c/x");
        assertThat(translate(U, "/home/me", V)).isEmpty();
    }

    @Test
    @DisplayName("배포판마다 마운트 루트가 다르면 그 배포판의 루트로 바꾼다 — /win/c/x ↔ C:\\x")
    void perDistroMountRoot() {
        // given
        WslMounts mounts = WslMounts.DEFAULT.with("U", new WslMount("/win/"));

        // when / then
        assertThat(PathTranslator.translate(path(U, "/win/c/x"), WIN, mounts).map(WorkspacePath::path))
                .contains("C:\\x");
        assertThat(PathTranslator.translate(path(WIN, "C:\\x"), U, mounts).map(WorkspacePath::path))
                .contains("/win/c/x");
        assertThat(PathTranslator.translate(path(U, "/mnt/c/x"), WIN, mounts)).isEmpty();
        assertThat(PathTranslator.translate(path(WIN, "C:\\x"), V, mounts).map(WorkspacePath::path))
                .contains("/mnt/c/x");
    }

    @Test
    @DisplayName("마운트 루트가 다른 배포판 사이에서는 드라이브를 거쳐 상대 배포판의 루트로 바꾼다")
    void otherDistroWithDifferentMountRoot() {
        // given
        WslMounts mounts = WslMounts.DEFAULT.with("U", new WslMount("/win/"));

        // when / then
        assertThat(PathTranslator.translate(path(U, "/win/c/x"), V, mounts).map(WorkspacePath::path))
                .contains("/mnt/c/x");
        assertThat(PathTranslator.translate(path(V, "/mnt/d"), U, mounts).map(WorkspacePath::path))
                .contains("/win/d");
        assertThat(PathTranslator.translate(path(U, "/home/me"), V, mounts)).isEmpty();
    }

    @Test
    @DisplayName("마운트 루트는 / 로 시작하고 / 로 끝나야 한다")
    void mountRootShape() {
        assertThatIllegalArgumentException().isThrownBy(() -> new WslMount("mnt/"));
        assertThatIllegalArgumentException().isThrownBy(() -> new WslMount("/mnt"));
    }
}
