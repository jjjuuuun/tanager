package tanager.domain.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.shared.PaneId;

/** 레이아웃 트리 → 창별 사각형(#89, SDD §5.3 LayoutGeometry). */
class LayoutGeometryTest {
    private static final PaneId P1 = new PaneId("p1");
    private static final PaneId P2 = new PaneId("p2");
    private static final PaneId P3 = new PaneId("p3");
    private static final LayoutNode.Leaf L1 = new LayoutNode.Leaf(P1);
    private static final LayoutNode.Leaf L2 = new LayoutNode.Leaf(P2);
    private static final LayoutNode.Leaf L3 = new LayoutNode.Leaf(P3);

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
}
