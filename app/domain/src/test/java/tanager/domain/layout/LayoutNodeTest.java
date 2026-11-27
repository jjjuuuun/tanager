package tanager.domain.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.shared.PaneId;

/** 레이아웃 트리의 split · close(#87, SDD §5.3). */
class LayoutNodeTest {
    private static final PaneId P1 = new PaneId("p1");
    private static final PaneId P2 = new PaneId("p2");
    private static final PaneId P3 = new PaneId("p3");
    private static final PaneId P9 = new PaneId("p9");

    private static LayoutNode leaf(PaneId pane) {
        return new LayoutNode.Leaf(pane);
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
}
