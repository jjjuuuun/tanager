package tanager.ui.layout;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Cursor;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.layout.LayoutGeometry;
import tanager.domain.layout.LayoutNode;
import tanager.domain.layout.Orientation;
import tanager.domain.layout.Ratio;
import tanager.domain.layout.Rect;
import tanager.domain.layout.SplitPath;
import tanager.domain.shared.PaneId;

/** 경계선을 마우스로 끌어 크기를 바꾼다(#150, FR-LAY-11). 헤드리스에서 합성 마우스 이벤트를 경계선 층에 직접 보낸다. */
class LayoutViewTest {
    private static final int GAP = 6;
    private static final PaneId P1 = new PaneId("p1");
    private static final PaneId P2 = new PaneId("p2");

    private record Resize(SplitPath path, Ratio ratio) {}

    private final List<Resize> resizes = new ArrayList<>();
    private final JPanel left = new JPanel();
    private final JPanel right = new JPanel();

    /** p1 | p2 를 606 × 400 에 놓는다 — 경계선 띠는 x 300~306(가운데 303). */
    private LayoutView view(Orientation orientation) {
        LayoutNode tree =
                new LayoutNode.Split(orientation, Ratio.HALF, new LayoutNode.Leaf(P1), new LayoutNode.Leaf(P2));
        LayoutView view = new LayoutView(
                tree, GAP, () -> new PaneMinimum(100, 40), (path, ratio) -> resizes.add(new Resize(path, ratio)));
        Map<PaneId, JComponent> components = new LinkedHashMap<>();
        components.put(P1, left);
        components.put(P2, right);
        view.component()
                .setSize(orientation == Orientation.ROW ? 606 : 400, orientation == Orientation.ROW ? 400 : 606);
        view.show(tree, components);
        return view;
    }

    private static void send(JComponent target, int id, int x, int y, int clicks) {
        int modifiers = id == MouseEvent.MOUSE_MOVED ? 0 : InputEvent.BUTTON1_DOWN_MASK;
        int button = id == MouseEvent.MOUSE_MOVED || id == MouseEvent.MOUSE_DRAGGED
                ? MouseEvent.NOBUTTON
                : MouseEvent.BUTTON1;
        target.dispatchEvent(
                new MouseEvent(target, id, System.currentTimeMillis(), modifiers, x, y, clicks, false, button));
    }

    @Test
    @DisplayName("창 영역은 배치가 쓰는 사각형과 같다 — 방향 포커스 · 교환이 화면과 같은 이웃을 찾는다")
    void areaMatchesLayout() {
        // given
        LayoutNode two =
                new LayoutNode.Leaf(P1).split(P1, Orientation.ROW, P2).value().orElseThrow();
        LayoutView view = new LayoutView(two, GAP, () -> new PaneMinimum(0, 0), (path, ratio) -> {});
        JComponent first = new JPanel();
        JComponent second = new JPanel();
        view.component().setSize(800, 600);
        view.show(two, Map.of(P1, first, P2, second));
        // 화면에 놓지 않은 컨테이너는 validate 가 배치하지 않는다(peer 없음) — 배치를 직접 돌린다
        view.component().doLayout();

        // when
        Rect area = view.area();

        // then
        assertThat(area).isEqualTo(new Rect(0, 0, 800, 600));
        Rect expected = LayoutGeometry.rects(two, area, GAP).get(P2);
        assertThat(second.getBounds())
                .isEqualTo(new Rectangle(expected.x(), expected.y(), expected.width(), expected.height()));
    }

    @Test
    @DisplayName("경계선 위에서는 좌우 크기 조절 커서, 창 위에서는 기본 커서다")
    void cursorOverRowDivider() {
        // given
        LayoutView view = view(Orientation.ROW);
        JComponent layer = view.dividerLayer();

        // when · then
        send(layer, MouseEvent.MOUSE_MOVED, 303, 100, 0);
        assertThat(layer.getCursor().getType()).isEqualTo(Cursor.E_RESIZE_CURSOR);
        send(layer, MouseEvent.MOUSE_MOVED, 100, 100, 0);
        assertThat(layer.getCursor().getType()).isEqualTo(Cursor.DEFAULT_CURSOR);
    }

    @Test
    @DisplayName("위아래로 나뉜 경계선 위에서는 위아래 크기 조절 커서다")
    void cursorOverColumnDivider() {
        // given: 위아래로 나뉘면 경계선 띠는 y 300~306
        LayoutView view = view(Orientation.COLUMN);
        JComponent layer = view.dividerLayer();

        // when
        send(layer, MouseEvent.MOUSE_MOVED, 100, 303, 0);

        // then
        assertThat(layer.getCursor().getType()).isEqualTo(Cursor.N_RESIZE_CURSOR);
    }

    @Test
    @DisplayName("경계선 층은 경계선 띠에서만 마우스를 받는다 — 나머지는 터미널이 받는다")
    void layerTakesOnlyDividerPoints() {
        // given
        JComponent layer = view(Orientation.ROW).dividerLayer();

        // when · then
        assertThat(layer.contains(303, 100)).isTrue();
        assertThat(layer.contains(100, 100)).isFalse();
        assertThat(layer.contains(500, 100)).isFalse();
    }

    @Test
    @DisplayName("끄는 동안에는 크기를 바꾸지 않는다 — 창 크기도 그대로, 콜백도 없다")
    void dragDoesNotResize() {
        // given
        LayoutView view = view(Orientation.ROW);
        JComponent layer = view.dividerLayer();

        // when
        send(layer, MouseEvent.MOUSE_PRESSED, 303, 100, 1);
        send(layer, MouseEvent.MOUSE_DRAGGED, 350, 100, 0);
        send(layer, MouseEvent.MOUSE_DRAGGED, 400, 100, 0);
        view.component().doLayout();

        // then
        assertThat(resizes).isEmpty();
        assertThat(left.getWidth()).isEqualTo(300);
        assertThat(right.getWidth()).isEqualTo(300);
        assertThat(view.preview()).isPresent();
    }

    @Test
    @DisplayName("놓으면 끈 자리의 비율로 콜백을 한 번 부르고 미리보기를 지운다")
    void releaseResizesOnce() {
        // given
        LayoutView view = view(Orientation.ROW);
        JComponent layer = view.dividerLayer();
        send(layer, MouseEvent.MOUSE_PRESSED, 303, 100, 1);
        send(layer, MouseEvent.MOUSE_DRAGGED, 350, 100, 0);
        send(layer, MouseEvent.MOUSE_DRAGGED, 453, 100, 0);

        // when
        send(layer, MouseEvent.MOUSE_RELEASED, 453, 100, 1);

        // then: 점을 간격 가운데로 본다 — first = 453 - 3 = 450 → 450 / 600
        assertThat(resizes).containsExactly(new Resize(SplitPath.ROOT, new Ratio(0.75)));
        assertThat(view.preview()).isEmpty();
    }

    @Test
    @DisplayName("끌면 최소 창 크기 안에서 멈춘다")
    void dragStopsAtMinimum() {
        // given: 창 최소 폭 100px
        LayoutView view = view(Orientation.ROW);
        JComponent layer = view.dividerLayer();

        // when
        send(layer, MouseEvent.MOUSE_PRESSED, 303, 100, 1);
        send(layer, MouseEvent.MOUSE_DRAGGED, 580, 100, 0);
        send(layer, MouseEvent.MOUSE_RELEASED, 580, 100, 1);

        // then: second ≥ 100 → first ≤ 500
        assertThat(resizes).containsExactly(new Resize(SplitPath.ROOT, new Ratio(500 / 600.0)));
    }

    @Test
    @DisplayName("끌지 않고 누르고 놓기만 하면 크기를 바꾸지 않는다")
    void clickWithoutDragDoesNothing() {
        // given
        JComponent layer = view(Orientation.ROW).dividerLayer();

        // when
        send(layer, MouseEvent.MOUSE_PRESSED, 303, 100, 1);
        send(layer, MouseEvent.MOUSE_RELEASED, 303, 100, 1);
        send(layer, MouseEvent.MOUSE_CLICKED, 303, 100, 1);

        // then
        assertThat(resizes).isEmpty();
    }

    @Test
    @DisplayName("경계선을 두 번 누르면 반반으로 돌린다")
    void doubleClickHalves() {
        // given
        JComponent layer = view(Orientation.ROW).dividerLayer();

        // when: 운영체제가 보내는 순서 그대로
        send(layer, MouseEvent.MOUSE_PRESSED, 303, 100, 1);
        send(layer, MouseEvent.MOUSE_RELEASED, 303, 100, 1);
        send(layer, MouseEvent.MOUSE_CLICKED, 303, 100, 1);
        send(layer, MouseEvent.MOUSE_PRESSED, 303, 100, 2);
        send(layer, MouseEvent.MOUSE_RELEASED, 303, 100, 2);
        send(layer, MouseEvent.MOUSE_CLICKED, 303, 100, 2);

        // then
        assertThat(resizes).containsExactly(new Resize(SplitPath.ROOT, Ratio.HALF));
    }

    @Test
    @DisplayName("끄는 중에 레이아웃이 바뀌면 드래그를 버린다 — 옛 경계선으로 크기를 바꾸지 않는다")
    void layoutChangeCancelsDrag() {
        // given
        LayoutView view = view(Orientation.ROW);
        JComponent layer = view.dividerLayer();
        send(layer, MouseEvent.MOUSE_PRESSED, 303, 100, 1);
        send(layer, MouseEvent.MOUSE_DRAGGED, 400, 100, 0);

        // when
        Map<PaneId, JComponent> one = Map.of(P1, left);
        view.show(new LayoutNode.Leaf(P1), one);
        send(layer, MouseEvent.MOUSE_RELEASED, 400, 100, 1);

        // then
        assertThat(resizes).isEmpty();
        assertThat(view.preview()).isEmpty();
    }

    /** p1 | p2(606 × 400)를 {@code zoom} 으로 다시 놓고 배치를 돌린다. */
    private void showZoomed(LayoutView view, Optional<PaneId> zoom) {
        LayoutNode tree =
                new LayoutNode.Split(Orientation.ROW, Ratio.HALF, new LayoutNode.Leaf(P1), new LayoutNode.Leaf(P2));
        Map<PaneId, JComponent> components = new LinkedHashMap<>();
        components.put(P1, left);
        components.put(P2, right);
        view.show(tree, zoom, components);
        // 화면에 놓지 않은 컨테이너는 validate 가 배치하지 않는다(peer 없음) — 배치를 직접 돌린다
        view.component().doLayout();
    }

    @Test
    @DisplayName("줌하면 그 창만 영역을 혼자 채우고 다른 창은 숨긴다 — 떼지 않는다(셸은 그대로, #149)")
    void zoomedPaneFillsArea() {
        // given
        LayoutView view = view(Orientation.ROW);

        // when
        showZoomed(view, Optional.of(P2));

        // then
        assertThat(right.isVisible()).isTrue();
        assertThat(right.getBounds()).isEqualTo(new Rectangle(0, 0, 606, 400));
        assertThat(left.isVisible()).isFalse();
        assertThat(left.getParent()).isSameAs(view.component());
    }

    @Test
    @DisplayName("줌한 동안에는 경계선이 없다 — 경계선 자리에서도 마우스를 받지 않고 끌어도 크기를 바꾸지 않는다(#149)")
    void zoomedHasNoDividers() {
        // given
        LayoutView view = view(Orientation.ROW);
        showZoomed(view, Optional.of(P2));
        JComponent layer = view.dividerLayer();

        // when
        send(layer, MouseEvent.MOUSE_MOVED, 303, 100, 0);
        send(layer, MouseEvent.MOUSE_PRESSED, 303, 100, 1);
        send(layer, MouseEvent.MOUSE_DRAGGED, 400, 100, 0);
        send(layer, MouseEvent.MOUSE_RELEASED, 400, 100, 1);

        // then
        assertThat(layer.contains(303, 100)).isFalse();
        assertThat(layer.getCursor().getType()).isEqualTo(Cursor.DEFAULT_CURSOR);
        assertThat(resizes).isEmpty();
        assertThat(view.preview()).isEmpty();
    }

    @Test
    @DisplayName("줌을 풀면 숨긴 창이 다시 보이고 원래 배치 · 경계선으로 돌아간다")
    void unzoomRestoresLayout() {
        // given
        LayoutView view = view(Orientation.ROW);
        showZoomed(view, Optional.of(P2));

        // when
        showZoomed(view, Optional.empty());

        // then
        Rect first = LayoutGeometry.rects(
                        new LayoutNode.Split(
                                Orientation.ROW, Ratio.HALF, new LayoutNode.Leaf(P1), new LayoutNode.Leaf(P2)),
                        view.area(),
                        GAP)
                .get(P1);
        assertThat(left.isVisible()).isTrue();
        assertThat(left.getBounds()).isEqualTo(new Rectangle(first.x(), first.y(), first.width(), first.height()));
        assertThat(right.getBounds().width).isLessThan(606);
        assertThat(view.dividerLayer().contains(303, 100)).isTrue();
    }
}
