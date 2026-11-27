package tanager.domain.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.layout.LayoutError;
import tanager.domain.layout.LayoutNode;
import tanager.domain.layout.Orientation;
import tanager.domain.layout.Ratio;
import tanager.domain.session.ProfileId;
import tanager.domain.shared.PaneId;

/** 탭 하나의 작업 공간: 열기 · 닫기 · 포커스(#88, SDD §5.4). */
class WorkspaceTest {
    private static final PaneId P1 = new PaneId("p1");
    private static final PaneId P2 = new PaneId("p2");
    private static final PaneId P3 = new PaneId("p3");
    private static final ProfileId PWSH = new ProfileId("powershell");
    private static final WorkspacePath A =
            WorkspacePath.of(new ShellEnvironment.Windows(), "C:\\a").value().orElseThrow();

    private static Workspace opened(Workspace workspace, Orientation orientation) {
        return workspace.openBeside(orientation, PWSH, Optional.of(A)).workspace();
    }

    @Test
    @DisplayName("시작 상태는 창 p1 하나이고 p1 에 포커스, 명세는 기억한 셸 · 시작 경로 그대로다")
    void initial() {
        // when
        Workspace workspace = Workspace.initial();

        // then
        assertThat(workspace.tab().root()).isEqualTo(new LayoutNode.Leaf(P1));
        assertThat(workspace.tab().focused()).isEqualTo(P1);
        assertThat(workspace.spec(P1)).contains(PaneSpec.remembered(P1));
    }

    @Test
    @DisplayName("오른쪽에 열면 포커스 창을 ROW 로 나누고 새 창 p2 에 포커스, 명세는 (프로파일, 경로)다")
    void openRight() {
        // when
        Workspace.Opened opened = Workspace.initial().openBeside(Orientation.ROW, PWSH, Optional.of(A));

        // then
        assertThat(opened.pane()).isEqualTo(P2);
        assertThat(opened.workspace().tab().root())
                .isEqualTo(new LayoutNode.Split(
                        Orientation.ROW, Ratio.HALF, new LayoutNode.Leaf(P1), new LayoutNode.Leaf(P2)));
        assertThat(opened.workspace().tab().focused()).isEqualTo(P2);
        assertThat(opened.workspace().spec(P2)).contains(new PaneSpec(P2, Optional.of(PWSH), Optional.of(A)));
    }

    @Test
    @DisplayName("아래로 열면 COLUMN 으로 나눈다 · 닫았던 번호는 다시 쓰지 않는다")
    void openDownAndFreshIds() {
        // given
        Workspace two = opened(Workspace.initial(), Orientation.ROW);
        Workspace closed = two.close(P2).value().orElseThrow();

        // when
        Workspace.Opened again = closed.openBeside(Orientation.COLUMN, PWSH, Optional.of(A));

        // then
        assertThat(again.pane()).isEqualTo(P3);
        assertThat(again.workspace().tab().root())
                .isEqualTo(new LayoutNode.Split(
                        Orientation.COLUMN, Ratio.HALF, new LayoutNode.Leaf(P1), new LayoutNode.Leaf(P3)));
    }

    @Test
    @DisplayName("포커스 창을 닫으면 남는 형제 쪽의 첫 창으로 포커스가 가고 명세도 지운다")
    void closeFocusedMovesFocusToSibling() {
        // given: p1 | (p2 / p3), 포커스 p3 → p2 를 포커스하고 닫는다
        Workspace w = opened(Workspace.initial(), Orientation.ROW); // p1 | p2 (포커스 p2)
        w = opened(w, Orientation.COLUMN); // p1 | (p2 / p3) (포커스 p3)
        w = w.focus(P2).value().orElseThrow();

        // when
        Workspace closed = w.close(P2).value().orElseThrow();

        // then
        assertThat(closed.tab().focused()).isEqualTo(P3);
        assertThat(closed.spec(P2)).isEmpty();
        assertThat(closed.tab().root().panes()).containsExactly(P1, P3);
    }

    @Test
    @DisplayName("포커스가 아닌 창을 닫으면 포커스는 그대로다 · 왼쪽 창을 닫으면 오른쪽 부분 트리의 첫 창")
    void closeOther() {
        // given
        Workspace w = opened(opened(Workspace.initial(), Orientation.ROW), Orientation.COLUMN); // p1 | (p2/p3), 포커스 p3

        // then
        assertThat(w.close(P2).value().orElseThrow().tab().focused()).isEqualTo(P3);
        Workspace leftFocused = w.focus(P1).value().orElseThrow();
        assertThat(leftFocused.close(P1).value().orElseThrow().tab().focused()).isEqualTo(P2);
    }

    @Test
    @DisplayName("마지막 창은 LastPane, 없는 창은 UnknownPane")
    void closeErrors() {
        assertThat(Workspace.initial().close(P1).error()).contains(new LayoutError.LastPane(P1));
        assertThat(Workspace.initial().close(P3).error()).contains(new LayoutError.UnknownPane(P3));
        assertThat(Workspace.initial().focus(P3).error()).contains(new LayoutError.UnknownPane(P3));
    }

    @Test
    @DisplayName("탭의 포커스는 트리 안의 창이어야 한다")
    void tabInvariant() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Tab(new LayoutNode.Leaf(P1), P2));
    }

    @Test
    @DisplayName("탭은 줌 없이 시작하고, 줌한 창은 트리 안의 창이어야 한다")
    void tabZoomInvariant() {
        // given
        LayoutNode two =
                new LayoutNode.Split(Orientation.ROW, Ratio.HALF, new LayoutNode.Leaf(P1), new LayoutNode.Leaf(P2));

        // then
        assertThat(new Tab(two, P1).zoomed()).isEmpty();
        assertThat(Workspace.initial().tab().zoomed()).isEmpty();
        assertThat(new Tab(two, P1, Optional.of(P2)).zoomed()).contains(P2);
        assertThatIllegalArgumentException().isThrownBy(() -> new Tab(two, P1, Optional.of(P3)));
    }

    @Test
    @DisplayName("줌 상태에서 창을 닫으면 줌한 창이든 다른 창이든 줌이 풀린다")
    void closeClearsZoom() {
        // given: p1 | (p2 / p3), 포커스 p3, p3 줌
        Workspace zoomedOnP3 = opened(opened(Workspace.initial(), Orientation.ROW), Orientation.COLUMN)
                .toggleZoom();

        // when
        Workspace closedZoomed = zoomedOnP3.close(P3).value().orElseThrow();
        Workspace closedOther = zoomedOnP3.close(P1).value().orElseThrow();

        // then
        assertThat(closedZoomed.tab().zoomed()).isEmpty();
        assertThat(closedOther.tab().zoomed()).isEmpty();
        assertThat(closedOther.tab().focused()).isEqualTo(P3);
    }

    @Test
    @DisplayName("명세와 트리의 창이 다르거나, 명세의 창이 키와 다르거나, 다음 번호가 0 이면 만들 수 없다")
    void workspaceInvariants() {
        Tab one = new Tab(new LayoutNode.Leaf(P1), P1);
        assertThatIllegalArgumentException().isThrownBy(() -> new Workspace(one, java.util.Map.of(), 2));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Workspace(one, java.util.Map.of(P1, PaneSpec.remembered(P2)), 2));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Workspace(one, java.util.Map.of(P1, PaneSpec.remembered(P1)), 0));
    }
}
