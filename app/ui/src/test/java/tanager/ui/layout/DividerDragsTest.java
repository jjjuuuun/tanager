package tanager.ui.layout;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.layout.Divider;
import tanager.domain.layout.LayoutGeometry;
import tanager.domain.layout.LayoutNode;
import tanager.domain.layout.Orientation;
import tanager.domain.layout.Ratio;
import tanager.domain.layout.Rect;
import tanager.domain.layout.SplitPath;
import tanager.domain.shared.PaneId;

/** 경계선 드래그의 순수 판정(#150, FR-LAY-11) — 어느 경계선을 잡는지, 미리보기 선의 자리, 하위 트리의 최소 크기. */
class DividerDragsTest {
    private static final int GAP = 6;
    private static final Rect BOUNDS = new Rect(0, 0, 606, 406);
    private static final LayoutNode.Leaf P1 = new LayoutNode.Leaf(new PaneId("p1"));
    private static final LayoutNode.Leaf P2 = new LayoutNode.Leaf(new PaneId("p2"));
    private static final LayoutNode.Leaf P3 = new LayoutNode.Leaf(new PaneId("p3"));

    /** p1 | (p2 / p3) */
    private static final LayoutNode NESTED = new LayoutNode.Split(
            Orientation.ROW, Ratio.HALF, P1, new LayoutNode.Split(Orientation.COLUMN, Ratio.HALF, P2, P3));

    private static List<Divider> dividers(LayoutNode tree) {
        return LayoutGeometry.dividers(tree, BOUNDS, GAP, LayoutView.HIT_PX);
    }

    @Test
    @DisplayName("점이 경계선 띠 안이면 그 경계선, 창 안쪽이면 없다")
    void dividerUnderPoint() {
        // given: 루트 세로 경계선은 x 300~306, 오른쪽의 가로 경계선은 y 200~206
        List<Divider> dividers = dividers(NESTED);

        // when · then
        assertThat(DividerDrags.at(dividers, 303, 100).map(Divider::path)).contains(SplitPath.ROOT);
        assertThat(DividerDrags.at(dividers, 450, 203).map(Divider::path))
                .contains(SplitPath.of(SplitPath.Branch.SECOND));
        assertThat(DividerDrags.at(dividers, 100, 100)).isEmpty();
        assertThat(DividerDrags.at(dividers, 450, 100)).isEmpty();
    }

    @Test
    @DisplayName("미리보기 선은 그 비율로 그렸을 때 간격이 올 자리다")
    void previewIsWhereTheGapWouldBe() {
        // given: 600px 를 나누는 루트 세로 경계선
        Divider root = dividers(NESTED).getFirst();

        // when
        Rect preview = DividerDrags.preview(root, new Ratio(0.25));

        // then: first = 600 × 0.25 = 150 → 간격 150~156, 높이는 영역 전체
        assertThat(preview).isEqualTo(new Rect(150, 0, GAP, 406));
    }

    @Test
    @DisplayName("가로 경계선의 미리보기는 가로 띠다")
    void columnPreview() {
        // given: 오른쪽 (p2 / p3) 의 경계선 — 영역 x 306~606, 높이 406
        Divider inner = dividers(NESTED).get(1);

        // when
        Rect preview = DividerDrags.preview(inner, new Ratio(0.75));

        // then: first = 400 × 0.75 = 300
        assertThat(preview).isEqualTo(new Rect(306, 300, 300, GAP));
    }

    @Test
    @DisplayName("하위 트리의 최소 크기는 그 축으로 늘어선 창 수만큼 쌓인다")
    void minimumsStackAlongTheAxis() {
        // given: (p1 | p2) | p3 — 루트 왼쪽에는 창이 가로로 둘
        LayoutNode tree = new LayoutNode.Split(
                Orientation.ROW, Ratio.HALF, new LayoutNode.Split(Orientation.ROW, Ratio.HALF, P1, P2), P3);
        Divider root = dividers(tree).getFirst();

        // when
        DividerDrags.Minimums minimums = DividerDrags.minimums(tree, root, new PaneMinimum(100, 40));

        // then: 왼쪽 = 100 + 6 + 100, 오른쪽 = 100
        assertThat(minimums).isEqualTo(new DividerDrags.Minimums(206, 100));
    }

    @Test
    @DisplayName("다른 축으로 나뉜 하위 트리는 큰 쪽 최소 크기를 따른다")
    void crossAxisTakesTheLarger() {
        // given: p1 | (p2 / p3) — 오른쪽은 위아래로 나뉘어 폭은 창 하나분
        Divider root = dividers(NESTED).getFirst();

        // when
        DividerDrags.Minimums minimums = DividerDrags.minimums(NESTED, root, new PaneMinimum(100, 40));

        // then
        assertThat(minimums).isEqualTo(new DividerDrags.Minimums(100, 100));
    }

    @Test
    @DisplayName("안쪽 경계선의 최소 크기는 그 Split 의 두 쪽 기준이다")
    void innerDividerMinimums() {
        // given
        Divider inner = dividers(NESTED).get(1);

        // when
        DividerDrags.Minimums minimums = DividerDrags.minimums(NESTED, inner, new PaneMinimum(100, 40));

        // then
        assertThat(minimums).isEqualTo(new DividerDrags.Minimums(40, 40));
    }
}
