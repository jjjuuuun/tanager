package tanager.domain.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.shared.PaneId;

/** 레이아웃 트리의 split · close(#87) · resize · resizeTo · swap · move(#146, SDD §5.3). */
class LayoutNodeTest {
    private static final PaneId P1 = new PaneId("p1");
    private static final PaneId P2 = new PaneId("p2");
    private static final PaneId P3 = new PaneId("p3");
    private static final PaneId P9 = new PaneId("p9");

    private static LayoutNode leaf(PaneId pane) {
        return new LayoutNode.Leaf(pane);
    }

    private static LayoutNode row(double ratio, LayoutNode first, LayoutNode second) {
        return new LayoutNode.Split(Orientation.ROW, new Ratio(ratio), first, second);
    }

    private static LayoutNode column(double ratio, LayoutNode first, LayoutNode second) {
        return new LayoutNode.Split(Orientation.COLUMN, new Ratio(ratio), first, second);
    }

    private static double rootRatio(LayoutNode tree) {
        return ((LayoutNode.Split) tree).ratio().value();
    }

    private static LayoutNode ok(tanager.domain.shared.Result<LayoutNode, LayoutError> result) {
        return result.value().orElseThrow(() -> new AssertionError("오류: " + result.error()));
    }

    @Test
    @DisplayName("창 하나를 가로로 나누면 반반인 Split 이 되고 새 창은 오른쪽이다")
    void splitLeaf() {
        // when
        LayoutNode split = ok(leaf(P1).split(P1, Orientation.ROW, P2));

        // then
        assertThat(split).isEqualTo(new LayoutNode.Split(Orientation.ROW, Ratio.HALF, leaf(P1), leaf(P2)));
        assertThat(split.panes()).containsExactly(P1, P2);
    }

    @Test
    @DisplayName("둘로 나눈 트리에서 한쪽을 닫으면 남은 창 하나가 된다")
    void closeEitherSide() {
        // given
        LayoutNode split = ok(leaf(P1).split(P1, Orientation.ROW, P2));

        // then
        assertThat(ok(split.close(P2))).isEqualTo(leaf(P1));
        assertThat(ok(split.close(P1))).isEqualTo(leaf(P2));
    }

    @Test
    @DisplayName("마지막 창은 닫을 수 없다")
    void lastPaneCannotClose() {
        assertThat(leaf(P1).close(P1).error()).contains(new LayoutError.LastPane(P1));
    }

    @Test
    @DisplayName("없는 창을 나누거나 닫으면 UnknownPane, 이미 있는 창 ID 로 나누면 DuplicatePane")
    void unknownAndDuplicate() {
        // given
        LayoutNode split = ok(leaf(P1).split(P1, Orientation.ROW, P2));

        // then
        assertThat(split.split(P9, Orientation.COLUMN, P3).error()).contains(new LayoutError.UnknownPane(P9));
        assertThat(split.close(P9).error()).contains(new LayoutError.UnknownPane(P9));
        assertThat(split.split(P1, Orientation.COLUMN, P2).error()).contains(new LayoutError.DuplicatePane(P2));
    }

    @Test
    @DisplayName("안쪽 Split 의 창을 닫으면 그 Split 이 형제로 줄고 바깥 비율은 그대로다")
    void closeInnerKeepsOuterRatio() {
        // given: Split(ROW, 0.3, p1, Split(COLUMN, p2, p3))
        LayoutNode tree = new LayoutNode.Split(
                Orientation.ROW,
                new Ratio(0.3),
                leaf(P1),
                new LayoutNode.Split(Orientation.COLUMN, Ratio.HALF, leaf(P2), leaf(P3)));

        // when
        LayoutNode closed = ok(tree.close(P2));

        // then
        assertThat(closed).isEqualTo(new LayoutNode.Split(Orientation.ROW, new Ratio(0.3), leaf(P1), leaf(P3)));
    }

    @Test
    @DisplayName("panes() 는 왼쪽 → 오른쪽, 위 → 아래 순서다")
    void panesInReadingOrder() {
        // given
        LayoutNode tree = ok(ok(leaf(P1).split(P1, Orientation.ROW, P2)).split(P1, Orientation.COLUMN, P3));

        // then: p1 이 위아래로 나뉘어 p1 · p3 가 왼쪽, p2 가 오른쪽
        assertThat(tree.panes()).isEqualTo(List.of(P1, P3, P2));
    }

    @Test
    @DisplayName("비율은 0.1 ~ 0.9 이고 그 밖 · NaN 은 거부한다")
    void ratioBounds() {
        assertThat(new Ratio(0.1).value()).isEqualTo(0.1);
        assertThat(new Ratio(0.9).value()).isEqualTo(0.9);
        assertThatIllegalArgumentException().isThrownBy(() -> new Ratio(0.05));
        assertThatIllegalArgumentException().isThrownBy(() -> new Ratio(0.95));
        assertThatIllegalArgumentException().isThrownBy(() -> new Ratio(Double.NaN));
    }

    @Test
    @DisplayName("같은 창 ID 가 두 번 들어간 Split 은 만들 수 없다")
    void splitRejectsDuplicatePanes() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LayoutNode.Split(Orientation.ROW, Ratio.HALF, leaf(P1), leaf(P1)));
    }

    @Test
    @DisplayName("resize 의 방향은 경계가 움직이는 쪽이다 — p1 · p2 어느 쪽에서 RIGHT 로 키워도 0.55")
    void resizeMovesDividerTowardDirection() {
        // given
        LayoutNode tree = row(0.5, leaf(P1), leaf(P2));

        // when
        LayoutNode fromLeft = ok(tree.resize(P1, Direction.RIGHT, 0.05));
        LayoutNode fromRight = ok(tree.resize(P2, Direction.RIGHT, 0.05));

        // then
        assertThat(rootRatio(fromLeft)).isCloseTo(0.55, within(1e-9));
        assertThat(rootRatio(fromRight)).isCloseTo(0.55, within(1e-9));
        assertThat(rootRatio(ok(tree.resize(P1, Direction.LEFT, 0.05)))).isCloseTo(0.45, within(1e-9));
    }

    @Test
    @DisplayName("resize 는 0.1 ~ 0.9 로 고정한다 — 0.9 에서 더 키워도 0.9, 0.1 에서 더 줄여도 0.1")
    void resizeClamps() {
        assertThat(rootRatio(ok(row(0.9, leaf(P1), leaf(P2)).resize(P1, Direction.RIGHT, 0.05))))
                .isEqualTo(0.9);
        assertThat(rootRatio(ok(column(0.1, leaf(P1), leaf(P2)).resize(P2, Direction.UP, 0.05))))
                .isEqualTo(0.1);
    }

    @Test
    @DisplayName("resize 는 target 을 감싼 가장 가까운 같은 축 Split 만 바꾼다")
    void resizeNearestMatchingAncestor() {
        // given: Split(ROW, 0.5, p1, Split(COLUMN, 0.5, p2, p3))
        LayoutNode tree = row(0.5, leaf(P1), column(0.5, leaf(P2), leaf(P3)));

        // when
        LayoutNode wider = ok(tree.resize(P3, Direction.LEFT, 0.1));
        LayoutNode taller = ok(tree.resize(P3, Direction.UP, 0.1));

        // then: LEFT 는 바깥 ROW, UP 은 안쪽 COLUMN
        assertThat(rootRatio(wider)).isCloseTo(0.4, within(1e-9));
        assertThat(((LayoutNode.Split) wider).second()).isEqualTo(column(0.5, leaf(P2), leaf(P3)));
        assertThat(rootRatio(taller)).isEqualTo(0.5);
        assertThat(rootRatio(((LayoutNode.Split) taller).second())).isCloseTo(0.4, within(1e-9));
    }

    @Test
    @DisplayName("위아래로만 나뉜 트리를 좌우로 resize 하면 NoSplitInDirection, 없는 창은 UnknownPane")
    void resizeWithoutMatchingSplit() {
        // given
        LayoutNode tree = column(0.5, leaf(P1), leaf(P2));

        // then
        assertThat(tree.resize(P1, Direction.LEFT, 0.05).error())
                .contains(new LayoutError.NoSplitInDirection(P1, Direction.LEFT));
        assertThat(leaf(P1).resize(P1, Direction.DOWN, 0.05).error())
                .contains(new LayoutError.NoSplitInDirection(P1, Direction.DOWN));
        assertThat(tree.resize(P9, Direction.UP, 0.05).error()).contains(new LayoutError.UnknownPane(P9));
        assertThatIllegalArgumentException().isThrownBy(() -> tree.resize(P1, Direction.UP, Double.NaN));
        assertThatIllegalArgumentException().isThrownBy(() -> tree.resize(P1, Direction.UP, -0.05));
    }

    @Test
    @DisplayName("resizeTo 는 경로가 가리키는 Split 의 비율만 바꾼다")
    void resizeToPath() {
        // given: Split(ROW, 0.5, p1, Split(COLUMN, 0.5, p2, p3))
        LayoutNode tree = row(0.5, leaf(P1), column(0.5, leaf(P2), leaf(P3)));

        // when
        LayoutNode root = ok(tree.resizeTo(SplitPath.ROOT, new Ratio(0.3)));
        LayoutNode inner = ok(tree.resizeTo(SplitPath.of(SplitPath.Branch.SECOND), new Ratio(0.7)));

        // then
        assertThat(root).isEqualTo(row(0.3, leaf(P1), column(0.5, leaf(P2), leaf(P3))));
        assertThat(inner).isEqualTo(row(0.5, leaf(P1), column(0.7, leaf(P2), leaf(P3))));
    }

    @Test
    @DisplayName("resizeTo 의 경로가 창이나 트리 밖을 가리키면 UnknownSplit")
    void resizeToUnknownSplit() {
        // given
        LayoutNode tree = row(0.5, leaf(P1), leaf(P2));
        SplitPath toLeaf = SplitPath.of(SplitPath.Branch.FIRST);
        SplitPath beyond = SplitPath.of(SplitPath.Branch.FIRST, SplitPath.Branch.SECOND);

        // then
        assertThat(tree.resizeTo(toLeaf, Ratio.HALF).error()).contains(new LayoutError.UnknownSplit(toLeaf));
        assertThat(tree.resizeTo(beyond, Ratio.HALF).error()).contains(new LayoutError.UnknownSplit(beyond));
        assertThat(leaf(P1).resizeTo(SplitPath.ROOT, Ratio.HALF).error())
                .contains(new LayoutError.UnknownSplit(SplitPath.ROOT));
    }

    @Test
    @DisplayName("SplitPath 는 루트에서 한 칸씩 내려가며 만들고 밖에서 바꿀 수 없다")
    void splitPath() {
        // given
        List<SplitPath.Branch> branches = new java.util.ArrayList<>(List.of(SplitPath.Branch.FIRST));
        SplitPath path = new SplitPath(branches);

        // when
        branches.add(SplitPath.Branch.SECOND);

        // then
        assertThat(path).isEqualTo(SplitPath.ROOT.then(SplitPath.Branch.FIRST));
        assertThat(SplitPath.ROOT.branches()).isEmpty();
        assertThat(path.then(SplitPath.Branch.SECOND).branches())
                .containsExactly(SplitPath.Branch.FIRST, SplitPath.Branch.SECOND);
    }

    @Test
    @DisplayName("p1 | (p2 / p3) 에서 swap(p1, p3) 이면 p3 | (p2 / p1), 비율은 그대로")
    void swapPanes() {
        // given
        LayoutNode tree = row(0.3, leaf(P1), column(0.6, leaf(P2), leaf(P3)));

        // when
        LayoutNode swapped = ok(tree.swap(P1, P3));

        // then
        assertThat(swapped).isEqualTo(row(0.3, leaf(P3), column(0.6, leaf(P2), leaf(P1))));
    }

    @Test
    @DisplayName("swap 할 창이 같으면 SamePane, 없으면 UnknownPane")
    void swapErrors() {
        // given
        LayoutNode tree = row(0.5, leaf(P1), leaf(P2));

        // then
        assertThat(tree.swap(P1, P1).error()).contains(new LayoutError.SamePane(P1));
        assertThat(tree.swap(P1, P9).error()).contains(new LayoutError.UnknownPane(P9));
        assertThat(tree.swap(P9, P2).error()).contains(new LayoutError.UnknownPane(P9));
    }

    @Test
    @DisplayName("p1 | p2 | p3 에서 move(p3, p1, TOP) 이면 (p3 / p1) | p2, 창 집합은 같다")
    void moveToTop() {
        // given
        LayoutNode tree = row(0.5, leaf(P1), row(0.5, leaf(P2), leaf(P3)));

        // when
        LayoutNode moved = ok(tree.move(P3, P1, Edge.TOP));

        // then
        assertThat(moved).isEqualTo(row(0.5, column(0.5, leaf(P3), leaf(P1)), leaf(P2)));
        assertThat(Set.copyOf(moved.panes())).isEqualTo(Set.copyOf(tree.panes()));
    }

    @Test
    @DisplayName("move 는 edge 쪽에 반반으로 붙인다 — BOTTOM · RIGHT 는 옮긴 창이 second, LEFT 는 first")
    void moveEachEdge() {
        // given
        LayoutNode tree = row(0.3, leaf(P1), leaf(P2));

        // then
        assertThat(ok(tree.move(P1, P2, Edge.BOTTOM))).isEqualTo(column(0.5, leaf(P2), leaf(P1)));
        assertThat(ok(tree.move(P1, P2, Edge.RIGHT))).isEqualTo(row(0.5, leaf(P2), leaf(P1)));
        assertThat(ok(tree.move(P2, P1, Edge.LEFT))).isEqualTo(row(0.5, leaf(P2), leaf(P1)));
    }

    @Test
    @DisplayName("move 할 창과 대상이 같으면 SamePane, 어느 쪽이든 없으면 UnknownPane")
    void moveErrors() {
        // given
        LayoutNode tree = row(0.5, leaf(P1), leaf(P2));

        // then
        assertThat(tree.move(P1, P1, Edge.TOP).error()).contains(new LayoutError.SamePane(P1));
        assertThat(tree.move(P9, P1, Edge.TOP).error()).contains(new LayoutError.UnknownPane(P9));
        assertThat(tree.move(P1, P9, Edge.TOP).error()).contains(new LayoutError.UnknownPane(P9));
    }
}
