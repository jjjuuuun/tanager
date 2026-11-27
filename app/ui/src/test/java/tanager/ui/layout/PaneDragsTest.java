package tanager.ui.layout;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.layout.DropZone;
import tanager.domain.layout.Edge;
import tanager.domain.layout.LayoutNode;
import tanager.domain.layout.Orientation;
import tanager.domain.layout.Ratio;
import tanager.domain.layout.Rect;
import tanager.domain.shared.PaneId;

/** 창 · 폴더 끌어다 놓기의 순수 판정(#151, FR-LAY-12 · 13) — 끌기 시작, 놓을 곳, 놓을 곳을 보일 사각형. */
class PaneDragsTest {
    private static final int GAP = 6;
    private static final PaneId P1 = new PaneId("p1");
    private static final PaneId P2 = new PaneId("p2");
    /** p1 | p2 를 606 × 400 에 — p1 은 x 0~300, p2 는 x 306~606. */
    private static final Rect AREA = new Rect(0, 0, 606, 400);

    private static final LayoutNode TWO =
            new LayoutNode.Split(Orientation.ROW, Ratio.HALF, new LayoutNode.Leaf(P1), new LayoutNode.Leaf(P2));

    @Test
    @DisplayName("누른 곳에서 4px 이상 움직여야 끌기다 — 그보다 적으면 클릭 · 더블클릭으로 남는다")
    void startsBeyondThreshold() {
        // when · then
        assertThat(PaneDrags.started(10, 10, 13, 10)).isFalse();
        assertThat(PaneDrags.started(10, 10, 12, 13)).isFalse();
        assertThat(PaneDrags.started(10, 10, 14, 10)).isTrue();
        assertThat(PaneDrags.started(10, 10, 7, 7)).isTrue();
    }

    @Test
    @DisplayName("끄는 창은 다른 창의 띠 · 가운데에만 놓인다 — 자기 위 · 간격이면 놓을 곳이 없다")
    void paneZoneSkipsDraggedPaneAndGap() {
        // when · then: p2 의 왼쪽 25% 띠는 x 306~381
        assertThat(PaneDrags.paneZone(TWO, AREA, GAP, P1, 320, 200)).contains(new DropZone.Side(P2, Edge.LEFT));
        assertThat(PaneDrags.paneZone(TWO, AREA, GAP, P1, 456, 200)).contains(new DropZone.Center(P2));
        assertThat(PaneDrags.paneZone(TWO, AREA, GAP, P1, 100, 200)).isEmpty();
        assertThat(PaneDrags.paneZone(TWO, AREA, GAP, P1, 303, 200)).isEmpty();
    }

    @Test
    @DisplayName("누른 채로는 끌기가 아니고, 문턱을 넘으면 놓을 곳을 따라가며, 놓을 곳이 없는 데로 가면 비운다")
    void grabFollowsPointer() {
        // given
        PaneDrags.Grab pressed = new PaneDrags.Grab.Pressed(P1, 100, 10);

        // when
        PaneDrags.Grab still = PaneDrags.moved(pressed, TWO, AREA, GAP, 102, 11);
        PaneDrags.Grab over = PaneDrags.moved(still, TWO, AREA, GAP, 320, 200);
        PaneDrags.Grab back = PaneDrags.moved(over, TWO, AREA, GAP, 100, 200);

        // then
        assertThat(still).isEqualTo(pressed);
        assertThat(over).isEqualTo(new PaneDrags.Grab.Dragging(P1, Optional.of(new DropZone.Side(P2, Edge.LEFT))));
        assertThat(back).isEqualTo(new PaneDrags.Grab.Dragging(P1, Optional.empty()));
    }

    @Test
    @DisplayName("놓으면 끄는 중일 때만 그 자리로 옮긴다 — 누르고 놓기만 했으면 아무 일도 없다")
    void releasedOnlyWhenDragging() {
        // when · then
        assertThat(PaneDrags.released(new PaneDrags.Grab.Pressed(P1, 0, 0))).isEmpty();
        assertThat(PaneDrags.released(new PaneDrags.Grab.Dragging(P1, Optional.of(new DropZone.Center(P2)))))
                .contains(new DropZone.Center(P2));
        assertThat(PaneDrags.released(new PaneDrags.Grab.Dragging(P1, Optional.empty())))
                .isEmpty();
    }

    @Test
    @DisplayName("변에 놓으면 대상 창의 그쪽 반(간격 제외)을, 가운데면 대상 창 전체를 보인다")
    void highlightShowsWhereThePaneGoes() {
        // when · then: p2 = (306, 0, 300, 400). 반은 (300 - 6) / 2 = 147, 위아래는 (400 - 6) / 2 = 197
        assertThat(PaneDrags.highlight(TWO, Optional.empty(), AREA, GAP, new DropZone.Side(P2, Edge.LEFT)))
                .contains(new Rect(306, 0, 147, 400));
        assertThat(PaneDrags.highlight(TWO, Optional.empty(), AREA, GAP, new DropZone.Side(P2, Edge.RIGHT)))
                .contains(new Rect(459, 0, 147, 400));
        assertThat(PaneDrags.highlight(TWO, Optional.empty(), AREA, GAP, new DropZone.Side(P2, Edge.TOP)))
                .contains(new Rect(306, 0, 300, 197));
        assertThat(PaneDrags.highlight(TWO, Optional.empty(), AREA, GAP, new DropZone.Side(P2, Edge.BOTTOM)))
                .contains(new Rect(306, 203, 300, 197));
        assertThat(PaneDrags.highlight(TWO, Optional.empty(), AREA, GAP, new DropZone.Center(P2)))
                .contains(new Rect(306, 0, 300, 400));
    }

    @Test
    @DisplayName("트리에 없는 창의 놓을 곳은 보이지 않는다")
    void highlightUnknownTarget() {
        // when · then
        assertThat(PaneDrags.highlight(TWO, Optional.empty(), AREA, GAP, new DropZone.Center(new PaneId("p9"))))
                .isEmpty();
    }

    @Test
    @DisplayName("폴더는 어느 창에나 놓인다 — 줌한 동안에는 줌한 창이 영역 전체를 채운 모양으로 판정 · 표시한다")
    void folderZoneFollowsWhatIsShown() {
        // when · then: 줌이 없으면 p1 의 가운데(100 / 300)
        assertThat(PaneDrags.folderZone(TWO, Optional.empty(), AREA, GAP, 100, 200))
                .contains(new DropZone.Center(P1));
        // 줌한 p2 가 606 폭을 혼자 채운다 — 100 / 606 은 왼쪽 띠
        assertThat(PaneDrags.folderZone(TWO, Optional.of(P2), AREA, GAP, 100, 200))
                .contains(new DropZone.Side(P2, Edge.LEFT));
        assertThat(PaneDrags.highlight(TWO, Optional.of(P2), AREA, GAP, new DropZone.Side(P2, Edge.LEFT)))
                .contains(new Rect(0, 0, 300, 400));
        assertThat(PaneDrags.folderZone(TWO, Optional.empty(), AREA, GAP, 303, 200))
                .isEmpty();
    }
}
