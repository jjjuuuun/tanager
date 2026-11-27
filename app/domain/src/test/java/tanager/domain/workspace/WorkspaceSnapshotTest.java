package tanager.domain.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.layout.LayoutNode;
import tanager.domain.layout.Orientation;
import tanager.domain.layout.Ratio;
import tanager.domain.session.IntegrationMode;
import tanager.domain.session.LaunchTemplate;
import tanager.domain.session.LiveSessions;
import tanager.domain.session.ProfileId;
import tanager.domain.session.ShellKind;
import tanager.domain.session.ShellProfile;
import tanager.domain.session.TerminalSession;
import tanager.domain.session.WorkingDir;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.TabId;

/** 저장할 복원 단위 만들기(#178, SDD §5.4, FR-RES-02). */
class WorkspaceSnapshotTest {
    private static final ShellEnvironment UBUNTU = new ShellEnvironment.Wsl("Ubuntu");
    private static final ShellEnvironment WINDOWS = new ShellEnvironment.Windows();
    private static final ShellProfile BASH = profile("wsl:Ubuntu", UBUNTU, ShellKind.BASH);
    private static final ShellProfile PWSH = profile("powershell", WINDOWS, ShellKind.WINDOWS_POWERSHELL);
    private static final PaneId P1 = new PaneId("p1");
    private static final PaneId P2 = new PaneId("p2");
    private static final PaneId P3 = new PaneId("p3");
    private static final WindowState WINDOW = new WindowState(
            Optional.of(new Bounds(10, 20, 1200, 800)), false, new PanelState(false, 0), new PanelState(true, 300));

    private static ShellProfile profile(String id, ShellEnvironment env, ShellKind kind) {
        return new ShellProfile(
                new ProfileId(id), id, kind, env, new LaunchTemplate("shell.exe", List.of()), IntegrationMode.OSC7);
    }

    private static WorkspacePath path(ShellEnvironment env, String raw) {
        return WorkspacePath.of(env, raw).value().orElseThrow();
    }

    private static TerminalSession running(ShellProfile profile, WorkingDir dir) {
        return TerminalSession.start(profile, dir)
                .value()
                .orElseThrow()
                .launched(1)
                .value()
                .orElseThrow();
    }

    /** 탭 t1 = [p1 | p2], 탭 t2 = p3. p1 은 기억한 셸, p2 는 bash 를 /start 에서, p3 은 powershell 을 C:\work 에서. */
    private static Workspace workspace() {
        Tab first = new Tab(
                new TabId("t1"),
                new LayoutNode.Split(Orientation.ROW, Ratio.HALF, new LayoutNode.Leaf(P1), new LayoutNode.Leaf(P2)),
                P2);
        Tab second = new Tab(new TabId("t2"), new LayoutNode.Leaf(P3), P3);
        return new Workspace(
                List.of(first, second),
                1,
                Map.of(
                        P1, PaneSpec.remembered(P1),
                        P2, new PaneSpec(P2, Optional.of(BASH.id()), Optional.of(path(UBUNTU, "/start"))),
                        P3, new PaneSpec(P3, Optional.of(PWSH.id()), Optional.of(path(WINDOWS, "C:\\work")))),
                4,
                3);
    }

    @Test
    @DisplayName("셸이 알린 마지막 경로와 실제로 뜬 프로파일이 그 창의 명세가 된다")
    void reportedCwdAndLaunchedProfileReplaceSpec() {
        // given: p1 은 기억한 셸(bash)로 홈에서 떠 /home/me/한글 을 알렸다
        LiveSessions sessions = LiveSessions.empty()
                .launched(P1, running(BASH, new WorkingDir.Home()))
                .cwdReported(P1, path(UBUNTU, "/home/me/한글"))
                .orElseThrow();

        // when
        WorkspaceSnapshot snapshot = WorkspaceSnapshot.capture(workspace(), sessions, WINDOW);

        // then
        assertThat(snapshot.workspace().spec(P1))
                .contains(new PaneSpec(P1, Optional.of(BASH.id()), Optional.of(path(UBUNTU, "/home/me/한글"))));
    }

    @Test
    @DisplayName("알린 경로가 없으면 시작 경로는 명세 그대로이고 프로파일만 실제로 뜬 것이 된다")
    void withoutReportStartDirStays() {
        // given: p1 은 기억한 셸로 떴지만 아직 경로를 알리지 않았다
        LiveSessions sessions = LiveSessions.empty().launched(P1, running(BASH, new WorkingDir.Home()));

        // when
        WorkspaceSnapshot snapshot = WorkspaceSnapshot.capture(workspace(), sessions, WINDOW);

        // then
        assertThat(snapshot.workspace().spec(P1)).contains(new PaneSpec(P1, Optional.of(BASH.id()), Optional.empty()));
    }

    @Test
    @DisplayName("끝난 셸도 마지막으로 알린 경로를 남긴다")
    void exitedSessionKeepsLastReported() {
        // given
        LiveSessions sessions = LiveSessions.empty()
                .launched(P3, running(PWSH, new WorkingDir.At(path(WINDOWS, "C:\\work"))))
                .cwdReported(P3, path(WINDOWS, "C:\\work\\infra"))
                .orElseThrow()
                .exited(P3, 0);

        // when
        WorkspaceSnapshot snapshot = WorkspaceSnapshot.capture(workspace(), sessions, WINDOW);

        // then
        assertThat(snapshot.workspace().spec(P3).flatMap(PaneSpec::dir)).contains(path(WINDOWS, "C:\\work\\infra"));
    }

    @Test
    @DisplayName("세션이 없는 창의 명세 · 탭 · 활성 탭 · 포커스 · 번호와 창 상태는 그대로다")
    void everythingElseStays() {
        // given: 작업 공간에 없는 창의 세션도 섞여 있다
        LiveSessions sessions = LiveSessions.empty().launched(new PaneId("p9"), running(BASH, new WorkingDir.Home()));

        // when
        WorkspaceSnapshot snapshot = WorkspaceSnapshot.capture(workspace(), sessions, WINDOW);

        // then
        assertThat(snapshot.workspace()).isEqualTo(workspace());
        assertThat(snapshot.window()).isEqualTo(WINDOW);
    }

    @Test
    @DisplayName("스냅샷은 null 을 받지 않는다")
    void rejectsNull() {
        // then
        assertThatNullPointerException().isThrownBy(() -> new WorkspaceSnapshot(null, WINDOW));
        assertThatNullPointerException().isThrownBy(() -> new WorkspaceSnapshot(workspace(), null));
    }
}
