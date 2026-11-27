package tanager.domain.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.shared.PaneId;

/** 레이아웃 트리 → 창별 사각형(#89) · 이웃 창 · 경계선 · 놓을 곳 · 드래그 비율(#146, SDD §5.3 LayoutGeometry). */
class LayoutGeometryTest {
    private static final PaneId P1 = new PaneId("p1");
    private static final PaneId P2 = new PaneId("p2");
    private static final PaneId P3 = new PaneId("p3");
    private static final PaneId P4 = new PaneId("p4");
    private static final LayoutNode.Leaf L1 = new LayoutNode.Leaf(P1);
    private static final LayoutNode.Leaf L2 = new LayoutNode.Leaf(P2);
    private static final LayoutNode.Leaf L3 = new LayoutNode.Leaf(P3);
    private static final LayoutNode.Leaf L4 = new LayoutNode.Leaf(P4);
    private static final Rect SQUARE = new Rect(0, 0, 1006, 1006);

    // p1 | (p2 / p3), 바깥은 반반
    private static LayoutNode leftAndStack(double innerRatio) {
        return new LayoutNode.Split(
                Orientation.ROW,
                Ratio.HALF,
                L1,
                new LayoutNode.Split(Orientation.COLUMN, new Ratio(innerRatio), L2, L3));
    }

    @Test
    @DisplayName("창 하나는 영역 전체다")
    void leafFillsBounds() {
        assertThat(LayoutGeometry.rects(L1, new Rect(10, 20, 300, 200), 6))
                .isEqualTo(Map.of(P1, new Rect(10, 20, 300, 200)));
    }

    @Test
    @DisplayName("ROW 반반은 간격을 뺀 폭을 나눠 왼쪽 · 오른쪽에 둔다")
    void rowSplit() {
        // given
        LayoutNode tree = new LayoutNode.Split(Orientation.ROW, Ratio.HALF, L1, L2);

        // when
        Map<PaneId, Rect> rects = LayoutGeometry.rects(tree, new Rect(0, 0, 1006, 400), 6);

        // then
        assertThat(rects.get(P1)).isEqualTo(new Rect(0, 0, 500, 400));
        assertThat(rects.get(P2)).isEqualTo(new Rect(506, 0, 500, 400));
    }

    @Test
    @DisplayName("COLUMN 0.3 은 위가 30%, 안쪽 Split 은 그 영역 안에서 다시 나눈다")
    void nestedColumn() {
        // given: p1 / (p2 | p3)
        LayoutNode tree = new LayoutNode.Split(
                Orientation.COLUMN, new Ratio(0.3), L1, new LayoutNode.Split(Orientation.ROW, Ratio.HALF, L2, L3));

        // when
        Map<PaneId, Rect> rects = LayoutGeometry.rects(tree, new Rect(0, 0, 206, 1006), 6);

        // then
        assertThat(rects.get(P1)).isEqualTo(new Rect(0, 0, 206, 300));
        assertThat(rects.get(P2)).isEqualTo(new Rect(0, 306, 100, 700));
        assertThat(rects.get(P3)).isEqualTo(new Rect(106, 306, 100, 700));
    }

    @Test
    @DisplayName("결과의 순서는 panes() 순서다")
    void orderFollowsPanes() {
        LayoutNode tree = new LayoutNode.Split(
                Orientation.ROW, Ratio.HALF, new LayoutNode.Split(Orientation.COLUMN, Ratio.HALF, L1, L3), L2);
        assertThat(List.copyOf(
                        LayoutGeometry.rects(tree, new Rect(0, 0, 100, 100), 0).keySet()))
                .containsExactly(P1, P3, P2);
    }

    @Test
    @DisplayName("영역이 간격보다 작아도 음수 크기를 만들지 않는다")
    void tinyBoundsNeverNegative() {
        // given
        LayoutNode tree = new LayoutNode.Split(Orientation.ROW, Ratio.HALF, L1, L2);

        // when
        Map<PaneId, Rect> rects = LayoutGeometry.rects(tree, new Rect(0, 0, 3, 3), 6);

        // then
        assertThat(rects.values()).allSatisfy(r -> assertThat(r.width()).isGreaterThanOrEqualTo(0));
    }

    @Test
    @DisplayName("사각형 크기 · 간격은 음수일 수 없다")
    void rejectsNegative() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Rect(0, 0, -1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> LayoutGeometry.rects(L1, new Rect(0, 0, 1, 1), -1));
    }

    @Test
    @DisplayName("p1 | p2 에서 p1 의 오른쪽 이웃은 p2, 왼쪽은 없다")
    void neighborRightAndNone() {
        // given
        LayoutNode tree = new LayoutNode.Split(Orientation.ROW, Ratio.HALF, L1, L2);

        // then
        assertThat(LayoutGeometry.neighbor(tree, P1, Direction.RIGHT, SQUARE, 6))
                .contains(P2);
        assertThat(LayoutGeometry.neighbor(tree, P1, Direction.LEFT, SQUARE, 6)).isEmpty();
        assertThat(LayoutGeometry.neighbor(tree, P2, Direction.LEFT, SQUARE, 6)).contains(P1);
        assertThat(LayoutGeometry.neighbor(tree, P1, Direction.UP, SQUARE, 6)).isEmpty();
    }

    @Test
    @DisplayName("이웃이 여럿이면 수직 축으로 더 많이 겹치는 창, 같으면 위 · 왼쪽 창")
    void neighborLargestOverlapThenTopLeft() {
        // then: 반반이면 p2 · p3 가 똑같이 겹쳐 위의 p2
        assertThat(LayoutGeometry.neighbor(leftAndStack(0.5), P1, Direction.RIGHT, SQUARE, 6))
                .contains(P2);
        // then: 위가 30% 면 아래 p3 가 더 많이 겹친다
        assertThat(LayoutGeometry.neighbor(leftAndStack(0.3), P1, Direction.RIGHT, SQUARE, 6))
                .contains(P3);
        // then: 위아래 · 왼쪽
        assertThat(LayoutGeometry.neighbor(leftAndStack(0.5), P3, Direction.UP, SQUARE, 6))
                .contains(P2);
        assertThat(LayoutGeometry.neighbor(leftAndStack(0.5), P2, Direction.DOWN, SQUARE, 6))
                .contains(P3);
        assertThat(LayoutGeometry.neighbor(leftAndStack(0.5), P3, Direction.LEFT, SQUARE, 6))
                .contains(P1);
    }

    @Test
    @DisplayName("겹침이 같으면 더 가까운 창을 고른다")
    void neighborClosestOnTie() {
        // given: p1 | p2 | p3 — p1 오른쪽에 p2 · p3 가 똑같이 겹친다
        LayoutNode tree = new LayoutNode.Split(
                Orientation.ROW, new Ratio(0.3), L1, new LayoutNode.Split(Orientation.ROW, Ratio.HALF, L2, L3));

        // then
        assertThat(LayoutGeometry.neighbor(tree, P1, Direction.RIGHT, SQUARE, 6))
                .contains(P2);
        assertThat(LayoutGeometry.neighbor(tree, P3, Direction.LEFT, SQUARE, 6)).contains(P2);
    }

    @Test
    @DisplayName("모서리만 닿거나 겹치지 않는 창은 이웃이 아니고, 없는 창에서 출발하면 비어 있다")
    void neighborNeedsOverlap() {
        // given: (p1 / p2) | (p3 / p4), 왼쪽 위가 30%, 오른쪽 위가 70%
        LayoutNode tree = new LayoutNode.Split(
                Orientation.ROW,
                Ratio.HALF,
                new LayoutNode.Split(Orientation.COLUMN, new Ratio(0.3), L1, L2),
                new LayoutNode.Split(Orientation.COLUMN, new Ratio(0.7), L3, L4));

        // then: p1(위 30%) 오른쪽은 p3 뿐 — p4 는 p1 과 겹치지 않는다
        assertThat(LayoutGeometry.neighbor(tree, P1, Direction.RIGHT, SQUARE, 6))
                .contains(P3);
        // then: p2(아래 70%) 오른쪽은 p3 · p4 모두 겹치지만 p3 가 더 많이
        assertThat(LayoutGeometry.neighbor(tree, P2, Direction.RIGHT, SQUARE, 6))
                .contains(P3);
        assertThat(LayoutGeometry.neighbor(tree, new PaneId("p9"), Direction.RIGHT, SQUARE, 6))
                .isEmpty();
    }

    @Test
    @DisplayName("경계선마다 간격 가운데에 폭 hitPx 인 잡는 사각형과 SplitPath 가 있다")
    void dividersCenteredOnGap() {
        // when
        List<Divider> dividers = LayoutGeometry.dividers(leftAndStack(0.5), SQUARE, 6, 6);

        // then
        assertThat(dividers)
                .containsExactly(
                        new Divider(SplitPath.ROOT, Orientation.ROW, new Rect(500, 0, 6, 1006), SQUARE, 6),
                        new Divider(
                                SplitPath.of(SplitPath.Branch.SECOND),
                                Orientation.COLUMN,
                                new Rect(506, 500, 500, 6),
                                new Rect(506, 0, 500, 1006),
                                6));
    }

    @Test
    @DisplayName("잡는 폭이 간격보다 넓으면 양쪽으로 반씩 넓힌다 · 창 하나면 경계선이 없다")
    void dividerWiderThanGap() {
        // given
        LayoutNode tree = new LayoutNode.Split(Orientation.ROW, Ratio.HALF, L1, L2);

        // when
        List<Divider> dividers = LayoutGeometry.dividers(tree, new Rect(0, 0, 1004, 100), 4, 8);

        // then: 간격은 x 500 ~ 504, 잡는 곳은 498 ~ 506
        assertThat(dividers.getFirst().hit()).isEqualTo(new Rect(498, 0, 8, 100));
        assertThat(LayoutGeometry.dividers(L1, SQUARE, 6, 6)).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() -> LayoutGeometry.dividers(tree, SQUARE, 6, 0));
    }

    @Test
    @DisplayName("경계선 위의 점은 그 경계선의 SplitPath 로 찾는다")
    void pointOnDividerFindsPath() {
        // given
        List<Divider> dividers = LayoutGeometry.dividers(leftAndStack(0.5), SQUARE, 6, 6);

        // when
        Optional<SplitPath> outer = hitPath(dividers, 503, 200);
        Optional<SplitPath> inner = hitPath(dividers, 700, 503);
        Optional<SplitPath> none = hitPath(dividers, 200, 200);

        // then
        assertThat(outer).contains(SplitPath.ROOT);
        assertThat(inner).contains(SplitPath.of(SplitPath.Branch.SECOND));
        assertThat(none).isEmpty();
    }

    private static Optional<SplitPath> hitPath(List<Divider> dividers, int x, int y) {
        return dividers.stream()
                .filter(d -> d.hit().contains(x, y))
                .map(Divider::path)
                .findFirst();
    }

    @Test
    @DisplayName("ratioAt 은 점을 간격 가운데로 보고 비율을 낸다")
    void ratioAtFollowsPointer() {
        // given: 폭 1006, 간격 6 → 나눌 폭 1000
        Divider divider = LayoutGeometry.dividers(
                        new LayoutNode.Split(Orientation.ROW, Ratio.HALF, L1, L2), SQUARE, 6, 6)
                .getFirst();

        // then
        assertThat(LayoutGeometry.ratioAt(divider, 703, 40, 0, 0).value()).isCloseTo(0.7, within(1e-9));
        assertThat(LayoutGeometry.ratioAt(divider, 1005, 40, 0, 0).value()).isEqualTo(0.9);
        assertThat(LayoutGeometry.ratioAt(divider, -50, 40, 0, 0).value()).isEqualTo(0.1);
    }

    @Test
    @DisplayName("ratioAt 은 양쪽 최소 크기를 지킨다 — 그 비율로 그리면 창이 최소 크기 이상이다")
    void ratioAtKeepsMinimumSizes() {
        // given: 위아래, 높이 1006, 간격 6
        LayoutNode tree = new LayoutNode.Split(Orientation.COLUMN, Ratio.HALF, L1, L2);
        Divider divider = LayoutGeometry.dividers(tree, SQUARE, 6, 6).getFirst();

        // when
        Ratio low = LayoutGeometry.ratioAt(divider, 10, 20, 163, 0);
        Ratio high = LayoutGeometry.ratioAt(divider, 10, 990, 0, 217);

        // then
        assertThat(low.value()).isCloseTo(0.163, within(1e-9));
        assertThat(high.value()).isCloseTo(0.783, within(1e-9));
        assertThat(height(tree, low, P1)).isGreaterThanOrEqualTo(163);
        assertThat(height(tree, high, P2)).isGreaterThanOrEqualTo(217);
    }

    private static int height(LayoutNode tree, Ratio ratio, PaneId pane) {
        LayoutNode resized = tree.resizeTo(SplitPath.ROOT, ratio).value().orElseThrow();
        return LayoutGeometry.rects(resized, SQUARE, 6).get(pane).height();
    }

    @Test
    @DisplayName("최소 크기를 둘 다 지킬 수 없으면 최소 크기의 비로 나누고, 나눌 폭이 없으면 반반")
    void ratioAtInfeasibleSplitsByMinimums() {
        // given: 폭 106, 간격 6 → 나눌 폭 100
        LayoutNode tree = new LayoutNode.Split(Orientation.ROW, Ratio.HALF, L1, L2);
        Divider divider =
                LayoutGeometry.dividers(tree, new Rect(0, 0, 106, 50), 6, 6).getFirst();
        Divider empty =
                LayoutGeometry.dividers(tree, new Rect(0, 0, 4, 50), 6, 6).getFirst();

        // then
        assertThat(LayoutGeometry.ratioAt(divider, 90, 0, 60, 60)).isEqualTo(Ratio.HALF);
        assertThat(LayoutGeometry.ratioAt(divider, 90, 0, 20, 80).value()).isCloseTo(0.2, within(1e-9));
        assertThat(LayoutGeometry.ratioAt(empty, 3, 0, 0, 0)).isEqualTo(Ratio.HALF);
        assertThatIllegalArgumentException().isThrownBy(() -> LayoutGeometry.ratioAt(divider, 0, 0, -1, 0));
    }

    @Test
    @DisplayName("창 가장자리 25% 띠는 가장 가까운 변의 Side, 안쪽은 Center")
    void dropZoneBandsAndCenter() {
        // given: 창 하나 400 x 200
        Rect bounds = new Rect(0, 0, 400, 200);

        // then
        assertThat(LayoutGeometry.dropZone(L1, bounds, 6, 200, 100)).contains(new DropZone.Center(P1));
        assertThat(LayoutGeometry.dropZone(L1, bounds, 6, 200, 10)).contains(new DropZone.Side(P1, Edge.TOP));
        assertThat(LayoutGeometry.dropZone(L1, bounds, 6, 200, 190)).contains(new DropZone.Side(P1, Edge.BOTTOM));
        assertThat(LayoutGeometry.dropZone(L1, bounds, 6, 20, 100)).contains(new DropZone.Side(P1, Edge.LEFT));
        assertThat(LayoutGeometry.dropZone(L1, bounds, 6, 380, 100)).contains(new DropZone.Side(P1, Edge.RIGHT));
        // then: 띠 경계(폭의 25% = 100px) 바로 안쪽은 Center
        assertThat(LayoutGeometry.dropZone(L1, bounds, 6, 100, 100)).contains(new DropZone.Center(P1));
        assertThat(LayoutGeometry.dropZone(L1, bounds, 6, 99, 100)).contains(new DropZone.Side(P1, Edge.LEFT));
    }

    @Test
    @DisplayName("놓을 곳의 대상은 점이 있는 창이고, 간격 · 영역 밖이면 비어 있다")
    void dropZoneTargetsPaneUnderPoint() {
        // given: p1 | p2, 폭 1006, 간격 6
        LayoutNode tree = new LayoutNode.Split(Orientation.ROW, Ratio.HALF, L1, L2);

        // then
        assertThat(LayoutGeometry.dropZone(tree, SQUARE, 6, 750, 500)).contains(new DropZone.Center(P2));
        assertThat(LayoutGeometry.dropZone(tree, SQUARE, 6, 490, 500)).contains(new DropZone.Side(P1, Edge.RIGHT));
        assertThat(LayoutGeometry.dropZone(tree, SQUARE, 6, 503, 500)).isEmpty();
        assertThat(LayoutGeometry.dropZone(tree, SQUARE, 6, -1, 500)).isEmpty();
        assertThat(LayoutGeometry.dropZone(tree, SQUARE, 6, 500, 1006)).isEmpty();
    }

    @Test
    @DisplayName("사각형은 왼쪽 · 위 변을 포함하고 오른쪽 · 아래 변은 포함하지 않는다")
    void rectContainsHalfOpen() {
        // given
        Rect r = new Rect(10, 20, 30, 40);

        // then
        assertThat(r.contains(10, 20)).isTrue();
        assertThat(r.contains(39, 59)).isTrue();
        assertThat(r.contains(40, 30)).isFalse();
        assertThat(r.contains(20, 60)).isFalse();
        assertThat(r.contains(9, 30)).isFalse();
        assertThat(new Rect(0, 0, 0, 10).contains(0, 5)).isFalse();
    }

    @Test
    @DisplayName("DropZone 은 대상 창을 알려 주고, Divider 의 간격은 음수일 수 없다")
    void valueInvariants() {
        assertThat(new DropZone.Side(P1, Edge.TOP).target()).isEqualTo(P1);
        assertThat(new DropZone.Center(P2).target()).isEqualTo(P2);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Divider(SplitPath.ROOT, Orientation.ROW, SQUARE, SQUARE, -1));
    }
}
