package dev.tanager.domain.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tanager.domain.history.SessionLeaf;
import dev.tanager.domain.history.SessionStatus;
import dev.tanager.domain.history.ShellEnvironment;
import dev.tanager.domain.history.WorkspacePath;
import dev.tanager.domain.shared.PaneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LiveSessionsTest {
    private static final ShellEnvironment UBUNTU = new ShellEnvironment.Wsl("Ubuntu");
    private static final ShellProfile BASH = new ShellProfile(
            new ProfileId("wsl:Ubuntu"),
            "Ubuntu",
            ShellKind.BASH,
            UBUNTU,
            new LaunchTemplate("wsl.exe", List.of()),
            IntegrationMode.OSC7);
    private static final PaneId P1 = new PaneId("p1");
    private static final PaneId P2 = new PaneId("p2");
    private static final PaneId P3 = new PaneId("p3");
    private static final WorkspacePath A = wsl("/a");
    private static final WorkspacePath B = wsl("/b");

    private static WorkspacePath wsl(String raw) {
        return WorkspacePath.of(UBUNTU, raw).value().orElseThrow();
    }

    private static TerminalSession running(WorkingDir dir) {
        return TerminalSession.start(BASH, dir)
                .value()
                .orElseThrow()
                .launched(10)
                .value()
                .orElseThrow();
    }

    private static TerminalSession runningAtHome() {
        return running(new WorkingDir.Home());
    }

    private static SessionLeaf leaf(LiveSessions sessions, PaneId pane) {
        return sessions.leaves().stream()
                .filter(l -> l.pane().equals(pane))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("처음 띄운 창은 번호 1, 다음 창은 2 를 받는다")
    void numbersAreAssignedInLaunchOrder() {
        // when
        LiveSessions sessions =
                LiveSessions.empty().launched(P1, runningAtHome()).launched(P2, runningAtHome());

        // then
        assertThat(leaf(sessions, P1).number()).isEqualTo(1);
        assertThat(leaf(sessions, P2).number()).isEqualTo(2);
        assertThat(sessions.nextNumber()).isEqualTo(3);
    }

    @Test
    @DisplayName("홈에서 띄운 세션은 아직 경로를 모르고, 경로에서 띄운 세션은 그 경로를 가진다")
    void leafCwdFollowsStartDirectory() {
        // when
        LiveSessions sessions =
                LiveSessions.empty().launched(P1, runningAtHome()).launched(P2, running(new WorkingDir.At(A)));

        // then
        assertThat(leaf(sessions, P1).cwd()).isEmpty();
        assertThat(leaf(sessions, P2).cwd()).contains(A);
        assertThat(leaf(sessions, P1).status()).isEqualTo(new SessionStatus.Running());
        assertThat(leaf(sessions, P1).shellName()).isEqualTo("Ubuntu");
        assertThat(leaf(sessions, P1).env()).isEqualTo(UBUNTU);
    }

    @Test
    @DisplayName("처음 알린 경로는 변화로 돌려주고 세션의 현재 경로 · 마지막으로 알린 경로가 된다")
    void firstReportChangesSession() {
        // given
        LiveSessions sessions = LiveSessions.empty().launched(P1, runningAtHome());

        // when
        Optional<LiveSessions> next = sessions.cwdReported(P1, A);

        // then
        assertThat(next).isPresent();
        assertThat(leaf(next.get(), P1).cwd()).contains(A);
        assertThat(next.get().byPane().get(P1).lastReported()).contains(A);
    }

    @Test
    @DisplayName("시작 경로와 같은 경로라도 첫 보고는 변화다")
    void firstReportEqualToStartDirIsStillAChange() {
        // given
        LiveSessions sessions = LiveSessions.empty().launched(P1, running(new WorkingDir.At(A)));

        // when / then
        assertThat(sessions.cwdReported(P1, A)).isPresent();
    }

    @Test
    @DisplayName("마지막으로 알린 경로와 같은 경로는 변화 없음이다")
    void sameAsLastReportedIsNoChange() {
        // given
        LiveSessions sessions = LiveSessions.empty()
                .launched(P1, runningAtHome())
                .cwdReported(P1, A)
                .orElseThrow();

        // when / then
        assertThat(sessions.cwdReported(P1, A)).isEmpty();
    }

    @Test
    @DisplayName("다른 경로로 갔다가 돌아오면 다시 변화다")
    void returningToEarlierPathIsAChange() {
        // given
        LiveSessions atA = LiveSessions.empty()
                .launched(P1, runningAtHome())
                .cwdReported(P1, A)
                .orElseThrow();

        // when
        LiveSessions atB = atA.cwdReported(P1, B).orElseThrow();

        // then
        assertThat(atB.cwdReported(P1, A)).isPresent();
    }

    @Test
    @DisplayName("같은 경로 중복 판정은 창마다 따로다")
    void duplicateIsPerPane() {
        // given
        LiveSessions sessions = LiveSessions.empty()
                .launched(P1, runningAtHome())
                .launched(P2, runningAtHome())
                .cwdReported(P1, A)
                .orElseThrow();

        // when / then
        assertThat(sessions.cwdReported(P2, A)).isPresent();
    }

    @Test
    @DisplayName("모르는 창 · 끝난 창 · 환경이 다른 경로의 보고는 변화 없음이다")
    void unusableReportsAreNoChange() {
        // given
        LiveSessions sessions =
                LiveSessions.empty().launched(P1, runningAtHome()).launched(P2, runningAtHome());
        LiveSessions withExit = sessions.exited(P2, 0);
        WorkspacePath windows = WorkspacePath.of(new ShellEnvironment.Windows(), "C:\\work")
                .value()
                .orElseThrow();

        // when / then
        assertThat(withExit.cwdReported(P3, A)).isEmpty();
        assertThat(withExit.cwdReported(P2, A)).isEmpty();
        assertThat(withExit.cwdReported(P1, windows)).isEmpty();
    }

    @Test
    @DisplayName("끝난 세션은 종료 코드와 마지막 경로를 유지한다")
    void exitedKeepsLastCwd() {
        // given
        LiveSessions sessions = LiveSessions.empty()
                .launched(P1, runningAtHome())
                .cwdReported(P1, A)
                .orElseThrow();

        // when
        LiveSessions exited = sessions.exited(P1, 3);

        // then
        assertThat(leaf(exited, P1).status()).isEqualTo(new SessionStatus.Exited(3));
        assertThat(leaf(exited, P1).cwd()).contains(A);
        assertThat(leaf(exited, P1).number()).isEqualTo(1);
    }

    @Test
    @DisplayName("모르는 창이 끝났다고 알려도 아무것도 바뀌지 않는다")
    void exitedUnknownPaneIsIgnored() {
        // given
        LiveSessions sessions = LiveSessions.empty().launched(P1, runningAtHome());

        // when / then
        assertThat(sessions.exited(P2, 1)).isEqualTo(sessions);
    }

    @Test
    @DisplayName("끝난 창을 다시 띄우면 번호가 유지되고 마지막으로 알린 경로가 비워진다")
    void relaunchKeepsNumberAndClearsLastReported() {
        // given
        LiveSessions exited = LiveSessions.empty()
                .launched(P1, runningAtHome())
                .launched(P2, runningAtHome())
                .cwdReported(P2, A)
                .orElseThrow()
                .exited(P2, 0);

        // when
        LiveSessions relaunched = exited.launched(P2, running(new WorkingDir.At(A)));

        // then
        assertThat(leaf(relaunched, P2).number()).isEqualTo(2);
        assertThat(leaf(relaunched, P2).status()).isEqualTo(new SessionStatus.Running());
        assertThat(relaunched.byPane().get(P2).lastReported()).isEmpty();
        assertThat(relaunched.cwdReported(P2, A)).isPresent();
        assertThat(relaunched.nextNumber()).isEqualTo(3);
    }

    @Test
    @DisplayName("창을 지워도 그 번호는 다시 쓰지 않는다")
    void removedNumbersAreNotReused() {
        // given
        LiveSessions sessions =
                LiveSessions.empty().launched(P1, runningAtHome()).launched(P2, runningAtHome());

        // when
        LiveSessions after = sessions.removed(P2).launched(P3, runningAtHome());

        // then
        assertThat(after.byPane()).doesNotContainKey(P2);
        assertThat(leaf(after, P3).number()).isEqualTo(3);
    }

    @Test
    @DisplayName("leaves 는 번호 순이고 수정할 수 없다")
    void leavesAreOrderedByNumberAndImmutable() {
        // given
        LiveSessions sessions =
                LiveSessions.empty().launched(P2, runningAtHome()).launched(P1, runningAtHome());

        // when
        List<SessionLeaf> leaves = sessions.leaves();

        // then
        assertThat(leaves).extracting(SessionLeaf::pane).containsExactly(P2, P1);
        assertThatThrownBy(() -> leaves.add(leaves.get(0))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Running 이 아닌 세션으로는 띄움 등록을 할 수 없다")
    void launchedRequiresRunningSession() {
        // given
        TerminalSession starting =
                TerminalSession.start(BASH, new WorkingDir.Home()).value().orElseThrow();

        // when / then
        assertThatThrownBy(() -> LiveSessions.empty().launched(P1, starting))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("LiveSession 은 마지막으로 알린 경로의 환경이 세션 환경과 같아야 한다")
    void liveSessionRejectsForeignLastReported() {
        // given
        WorkspacePath windows = WorkspacePath.of(new ShellEnvironment.Windows(), "C:\\work")
                .value()
                .orElseThrow();

        // when / then
        assertThatThrownBy(() -> new LiveSession(P1, 1, runningAtHome(), Optional.of(windows)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LiveSession(P1, 0, runningAtHome(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
