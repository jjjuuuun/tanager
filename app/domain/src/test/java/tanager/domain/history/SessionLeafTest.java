package tanager.domain.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.shared.PaneId;

class SessionLeafTest {

    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();
    private static final ShellEnvironment WSL = new ShellEnvironment.Wsl("U");

    private static WorkspacePath path(ShellEnvironment env, String raw) {
        return WorkspacePath.of(env, raw).value().orElseThrow();
    }

    @Test
    @DisplayName("cwd 가 환경과 같으면 만들어지고 cwd 는 비어 있어도 된다")
    void valid() {
        // given
        var running = new SessionStatus.Running();

        // when
        var withCwd = new SessionLeaf(new PaneId("p1"), 1, "bash", WSL, Optional.of(path(WSL, "/a")), running);
        var atHome = new SessionLeaf(new PaneId("p1"), 1, "bash", WSL, Optional.empty(), running);

        // then
        assertThat(withCwd.number()).isEqualTo(1);
        assertThat(atHome.cwd()).isEmpty();
    }

    @Test
    @DisplayName("cwd 의 환경이 세션 환경과 다르면 거부한다")
    void rejectsCwdOfOtherEnvironment() {
        // when · then
        assertThatThrownBy(() -> new SessionLeaf(
                        new PaneId("p1"), 1, "bash", WSL, Optional.of(path(WIN, "C:\\a")), new SessionStatus.Running()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("번호가 1 보다 작으면 거부한다")
    void rejectsNumberBelowOne() {
        // when · then
        assertThatThrownBy(() -> new SessionLeaf(
                        new PaneId("p1"), 0, "bash", WSL, Optional.empty(), new SessionStatus.Running()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("종료된 세션은 종료 코드를 가진다")
    void exitedCarriesCode() {
        // when
        var exited = new SessionStatus.Exited(3);

        // then
        assertThat(exited.exitCode()).isEqualTo(3);
    }
}
