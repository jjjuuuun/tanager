package tanager.domain.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.layout.Direction;
import tanager.domain.layout.DropZone;
import tanager.domain.layout.Edge;
import tanager.domain.layout.LayoutError;
import tanager.domain.layout.LayoutNode;
import tanager.domain.layout.Orientation;
import tanager.domain.layout.Ratio;
import tanager.domain.layout.Rect;
import tanager.domain.layout.SplitPath;
import tanager.domain.session.ProfileId;
import tanager.domain.shared.PaneId;

/** 작업 공간의 분할 · 크기 · 방향 포커스 · 줌 · 교환 · 옮기기(#147, FR-LAY-01~06 · 12). */
class WorkspaceLayoutTest {
    private static final PaneId P1 = new PaneId("p1");
    private static final PaneId P2 = new PaneId("p2");
    private static final PaneId P3 = new PaneId("p3");
    private static final PaneId P9 = new PaneId("p9");
    private static final ProfileId CMD = new ProfileId("cmd");
    private static final ProfileId PWSH = new ProfileId("powershell");
    private static final WorkspacePath A =
            WorkspacePath.of(new ShellEnvironment.Windows(), "C:\\a").value().orElseThrow();
    private static final WorkspacePath B =
            WorkspacePath.of(new ShellEnvironment.Windows(), "C:\\b").value().orElseThrow();
    private static final Rect AREA = new Rect(0, 0, 200, 100);
    private static final int GAP = 6;

    /** p1 | p2, 포커스 p1. p2 의 명세는 (powershell, C:\b). */
    private static Workspace twoPanes() {
        return Workspace.initial()
                .openBeside(Orientation.ROW, PWSH, Optional.of(B))
                .workspace()
                .focus(P1)
                .value()
                .orElseThrow();
    }

    private static LayoutNode row(LayoutNode first, LayoutNode second) {
        return new LayoutNode.Split(Orientation.ROW, Ratio.HALF, first, second);
    }

    private static LayoutNode.Leaf leaf(PaneId pane) {
        return new LayoutNode.Leaf(pane);
    }

    @Test
    @DisplayName("split 은 포커스 창 옆에 주어진 프로파일 · 경로의 새 창을 열고 그 창에 포커스한다")
    void splitWithProfileAndDir() {
        // when
        Workspace.Opened opened = twoPanes().split(Orientation.COLUMN, Optional.of(CMD), Optional.of(A));

        // then: p1 이 위아래로 나뉜다
        assertThat(opened.pane()).isEqualTo(P3);
        assertThat(opened.workspace().tab().root())
                .isEqualTo(row(new LayoutNode.Split(Orientation.COLUMN, Ratio.HALF, leaf(P1), leaf(P3)), leaf(P2)));
        assertThat(opened.workspace().tab().focused()).isEqualTo(P3);
        assertThat(opened.workspace().spec(P3)).contains(new PaneSpec(P3, Optional.of(CMD), Optional.of(A)));
    }

    @Test
    @DisplayName("split 의 프로파일 · 경로가 비면 새 창 명세도 비어 기억한 셸을 그 시작 경로에서 띄운다")
    void splitWithoutProfile() {
        // when
        Workspace.Opened opened = Workspace.initial().split(Orientation.ROW, Optional.empty(), Optional.empty());

        // then
        assertThat(opened.workspace().spec(P2)).contains(PaneSpec.remembered(P2));
        assertThat(opened.workspace().tab().root()).isEqualTo(row(leaf(P1), leaf(P2)));
    }

    @Test
    @DisplayName("resize 는 포커스 창의 경계를 5% 옮기고, 그 방향의 Split 이 없으면 NoSplitInDirection 이다")
    void resize() {
        // when
        Workspace grown = twoPanes().resize(Direction.RIGHT).value().orElseThrow();

        // then
        assertThat(((LayoutNode.Split) grown.tab().root()).ratio().value()).isCloseTo(0.55, within());
        assertThat(grown.tab().focused()).isEqualTo(P1);
        assertThat(grown.specs()).isEqualTo(twoPanes().specs());
        assertThat(twoPanes().resize(Direction.UP).error())
                .contains(new LayoutError.NoSplitInDirection(P1, Direction.UP));
    }

    @Test
    @DisplayName("resizeTo 는 경로가 가리키는 Split 의 비율을 바꾸고, Split 이 아니면 UnknownSplit 이다")
    void resizeTo() {
        // when
        Workspace dragged =
                twoPanes().resizeTo(SplitPath.ROOT, new Ratio(0.3)).value().orElseThrow();

        // then
        assertThat(((LayoutNode.Split) dragged.tab().root()).ratio()).isEqualTo(new Ratio(0.3));
        SplitPath leafPath = SplitPath.of(SplitPath.Branch.FIRST);
        assertThat(twoPanes().resizeTo(leafPath, new Ratio(0.3)).error())
                .contains(new LayoutError.UnknownSplit(leafPath));
    }

    @Test
    @DisplayName("방향 포커스는 기하로 이웃 창을 고르고, 그쪽에 창이 없으면 NoNeighbor 이다")
    void focusDirection() {
        // when
        Workspace right = twoPanes().focus(Direction.RIGHT, AREA, GAP).value().orElseThrow();

        // then
        assertThat(right.tab().focused()).isEqualTo(P2);
        assertThat(right.tab().root()).isEqualTo(twoPanes().tab().root());
        assertThat(twoPanes().focus(Direction.LEFT, AREA, GAP).error())
                .contains(new LayoutError.NoNeighbor(P1, Direction.LEFT));
    }

    @Test
    @DisplayName("toggleZoom 은 포커스 창을 줌하고, 다시 부르면 푼다 · 창이 하나면 그대로다")
    void toggleZoom() {
        // when
        Workspace zoomed = twoPanes().toggleZoom();
        Workspace unzoomed = zoomed.toggleZoom();

        // then
        assertThat(zoomed.tab().zoomed()).contains(P1);
        assertThat(zoomed.tab().root()).isEqualTo(twoPanes().tab().root());
        assertThat(unzoomed).isEqualTo(twoPanes());
        assertThat(Workspace.initial().toggleZoom()).isEqualTo(Workspace.initial());
    }

    @Test
    @DisplayName("줌 상태에서 다른 창에 포커스하면 줌이 풀리고, 같은 창에 포커스하면 그대로다")
    void focusClearsZoomOnlyForOtherPane() {
        // given
        Workspace zoomed = twoPanes().toggleZoom();

        // when
        Workspace same = zoomed.focus(P1).value().orElseThrow();
        Workspace other = zoomed.focus(P2).value().orElseThrow();
        Workspace byDirection = zoomed.focus(Direction.RIGHT, AREA, GAP).value().orElseThrow();

        // then
        assertThat(same).isEqualTo(zoomed);
        assertThat(other.tab().zoomed()).isEmpty();
        assertThat(byDirection.tab().zoomed()).isEmpty();
        assertThat(byDirection.tab().focused()).isEqualTo(P2);
    }

    @Test
    @DisplayName("줌 상태에서 분할 · 크기 조절 · 교환 · 옮기기를 하면 줌이 풀린다")
    void layoutChangesClearZoom() {
        // given
        Workspace zoomed = twoPanes().toggleZoom();

        // then
        assertThat(zoomed.split(Orientation.ROW, Optional.empty(), Optional.empty())
                        .workspace()
                        .tab()
                        .zoomed())
                .isEmpty();
        assertThat(zoomed.resize(Direction.RIGHT).value().orElseThrow().tab().zoomed())
                .isEmpty();
        assertThat(zoomed.resizeTo(SplitPath.ROOT, new Ratio(0.3))
                        .value()
                        .orElseThrow()
                        .tab()
                        .zoomed())
                .isEmpty();
        assertThat(zoomed.swap(Direction.RIGHT, AREA, GAP)
                        .value()
                        .orElseThrow()
                        .tab()
                        .zoomed())
                .isEmpty();
        assertThat(zoomed.move(P2, new DropZone.Side(P1, Edge.TOP))
                        .value()
                        .orElseThrow()
                        .tab()
                        .zoomed())
                .isEmpty();
    }

    @Test
    @DisplayName("실패한 연산은 줌을 포함해 작업 공간을 바꾸지 않는다(오류만 돌려준다)")
    void failureKeepsZoom() {
        // given
        Workspace zoomed = twoPanes().toggleZoom();

        // then
        assertThat(zoomed.resize(Direction.UP).isOk()).isFalse();
        assertThat(zoomed.swap(Direction.LEFT, AREA, GAP).isOk()).isFalse();
        assertThat(zoomed.tab().zoomed()).contains(P1);
    }

    @Test
    @DisplayName("swap 은 포커스 창과 그 방향의 이웃을 바꾸고, 포커스는 옮겨 간 창에 남으며 명세는 창을 따라간다")
    void swapWithNeighbor() {
        // when
        Workspace swapped = twoPanes().swap(Direction.RIGHT, AREA, GAP).value().orElseThrow();

        // then
        assertThat(swapped.tab().root()).isEqualTo(row(leaf(P2), leaf(P1)));
        assertThat(swapped.tab().focused()).isEqualTo(P1);
        assertThat(swapped.specs()).isEqualTo(twoPanes().specs());
        assertThat(twoPanes().swap(Direction.LEFT, AREA, GAP).error())
                .contains(new LayoutError.NoNeighbor(P1, Direction.LEFT));
    }

    @Test
    @DisplayName("move 를 가운데에 놓으면 두 창을 바꾸고, 옮긴 창에 포커스한다")
    void moveToCenterSwaps() {
        // when
        Workspace moved = twoPanes().move(P2, new DropZone.Center(P1)).value().orElseThrow();

        // then
        assertThat(moved.tab().root()).isEqualTo(row(leaf(P2), leaf(P1)));
        assertThat(moved.tab().focused()).isEqualTo(P2);
        assertThat(moved.specs()).isEqualTo(twoPanes().specs());
    }

    @Test
    @DisplayName("move 를 변에 놓으면 대상 창의 그쪽에 붙이고(TOP 이면 위), 옮긴 창에 포커스한다")
    void moveToSide() {
        // when
        Workspace moved =
                twoPanes().move(P2, new DropZone.Side(P1, Edge.TOP)).value().orElseThrow();

        // then
        assertThat(moved.tab().root())
                .isEqualTo(new LayoutNode.Split(Orientation.COLUMN, Ratio.HALF, leaf(P2), leaf(P1)));
        assertThat(moved.tab().focused()).isEqualTo(P2);
        assertThat(moved.specs()).isEqualTo(twoPanes().specs());
    }

    @Test
    @DisplayName("move 의 두 창이 같으면 SamePane, 없는 창이면 UnknownPane 이다")
    void moveErrors() {
        assertThat(twoPanes().move(P1, new DropZone.Center(P1)).error()).contains(new LayoutError.SamePane(P1));
        assertThat(twoPanes().move(P9, new DropZone.Side(P1, Edge.LEFT)).error())
                .contains(new LayoutError.UnknownPane(P9));
        assertThat(twoPanes().move(P1, new DropZone.Center(P9)).error()).contains(new LayoutError.UnknownPane(P9));
    }

    private static org.assertj.core.data.Offset<Double> within() {
        return org.assertj.core.data.Offset.offset(1e-9);
    }
}
