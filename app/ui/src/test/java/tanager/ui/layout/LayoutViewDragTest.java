package tanager.ui.layout;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.BorderLayout;
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
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.layout.DropZone;
import tanager.domain.layout.Edge;
import tanager.domain.layout.LayoutNode;
import tanager.domain.layout.Orientation;
import tanager.domain.layout.Ratio;
import tanager.domain.layout.Rect;
import tanager.domain.shared.PaneId;

/**
 * 머리줄을 끌어 다른 창의 5영역에 놓기(FR-LAY-12)와 사이드바 폴더 놓기(FR-LAY-13, #151). 헤드리스에서 합성 마우스 이벤트를 창 안의 손잡이에
 * 직접 보낸다. 창 p1 | p2 는 606 × 400 — p1 은 x 0~300, p2 는 x 306~606 이고 손잡이는 각 창 위 20px 띠다.
 */
class LayoutViewDragTest {
    private static final int GAP = 6;
    private static final PaneId P1 = new PaneId("p1");
    private static final PaneId P2 = new PaneId("p2");
    private static final LayoutNode TWO =
            new LayoutNode.Split(Orientation.ROW, Ratio.HALF, new LayoutNode.Leaf(P1), new LayoutNode.Leaf(P2));
    private static final WorkspacePath WORK =
            WorkspacePath.of(new ShellEnvironment.Windows(), "C:\\work").value().orElseThrow();

    private record Move(PaneId pane, DropZone zone) {}

    private record Open(WorkspacePath path, DropZone zone) {}

    private final List<Move> moves = new ArrayList<>();
    private final List<Open> opens = new ArrayList<>();
    private final JPanel left = new JPanel(new BorderLayout());
    private final JPanel right = new JPanel(new BorderLayout());
    private final JPanel leftHandle = new JPanel();
    private final JPanel rightHandle = new JPanel();

    private LayoutView view() {
        LayoutView view = new LayoutView(
                TWO,
                GAP,
                () -> new PaneMinimum(100, 40),
                (path, ratio) -> {},
                new LayoutView.Drops(
                        (pane, zone) -> moves.add(new Move(pane, zone)),
                        (path, zone) -> opens.add(new Open(path, zone))));
        left.add(leftHandle, BorderLayout.NORTH);
        right.add(rightHandle, BorderLayout.NORTH);
        view.component().setSize(606, 400);
        show(view, Optional.empty());
        view.addDragHandle(leftHandle);
        view.addDragHandle(rightHandle);
        return view;
    }

    private void show(LayoutView view, Optional<PaneId> zoom) {
        Map<PaneId, JComponent> components = new LinkedHashMap<>();
        components.put(P1, left);
        components.put(P2, right);
        view.show(TWO, zoom, components);
        // 화면에 놓지 않은 컨테이너는 validate 가 배치하지 않는다(peer 없음) — 배치를 직접 돌린다
        view.component().doLayout();
        leftHandle.setBounds(0, 0, left.getWidth(), 20);
        rightHandle.setBounds(0, 0, right.getWidth(), 20);
    }

    /** 손잡이 안쪽 좌표로 보낸다. p1 의 손잡이는 창 영역 좌표와 같다. */
    private static void send(JComponent target, int id, int x, int y) {
        int modifiers = id == MouseEvent.MOUSE_RELEASED ? 0 : InputEvent.BUTTON1_DOWN_MASK;
        int button = id == MouseEvent.MOUSE_DRAGGED ? MouseEvent.NOBUTTON : MouseEvent.BUTTON1;
        int clicks = id == MouseEvent.MOUSE_DRAGGED ? 0 : 1;
        target.dispatchEvent(
                new MouseEvent(target, id, System.currentTimeMillis(), modifiers, x, y, clicks, false, button));
    }

    @Test
    @DisplayName("4px 보다 적게 움직이면 끌기가 아니다 — 놓을 곳도 보이지 않고 놓아도 옮기지 않는다")
    void belowThresholdIsNotADrag() {
        // given
        LayoutView view = view();

        // when
        send(leftHandle, MouseEvent.MOUSE_PRESSED, 100, 10);
        send(leftHandle, MouseEvent.MOUSE_DRAGGED, 103, 10);

        // then
        assertThat(view.draggingPane()).isFalse();
        assertThat(view.dropPreview()).isEmpty();

        // when
        send(leftHandle, MouseEvent.MOUSE_RELEASED, 103, 10);

        // then
        assertThat(moves).isEmpty();
    }

    @Test
    @DisplayName("다른 창 위로 끌면 그 창의 놓을 곳을 반투명으로 보이고, 움직이면 영역이 바뀐다")
    void previewFollowsZone() {
        // given
        LayoutView view = view();
        send(leftHandle, MouseEvent.MOUSE_PRESSED, 100, 10);

        // when: p2 의 왼쪽 띠
        send(leftHandle, MouseEvent.MOUSE_DRAGGED, 320, 200);

        // then
        assertThat(view.draggingPane()).isTrue();
        assertThat(view.dropPreview()).contains(new Rect(306, 0, 147, 400));

        // when: p2 의 가운데
        send(leftHandle, MouseEvent.MOUSE_DRAGGED, 456, 200);

        // then
        assertThat(view.dropPreview()).contains(new Rect(306, 0, 300, 400));
    }

    @Test
    @DisplayName("끄는 창 자신이나 간격 위에서는 아무것도 보이지 않고 놓아도 옮기지 않는다")
    void nothingOverSelfOrGap() {
        // given
        LayoutView view = view();
        send(leftHandle, MouseEvent.MOUSE_PRESSED, 100, 10);

        // when
        send(leftHandle, MouseEvent.MOUSE_DRAGGED, 150, 200);
        Optional<Rect> overSelf = view.dropPreview();
        send(leftHandle, MouseEvent.MOUSE_DRAGGED, 303, 200);
        Optional<Rect> overGap = view.dropPreview();
        send(leftHandle, MouseEvent.MOUSE_RELEASED, 303, 200);

        // then
        assertThat(overSelf).isEmpty();
        assertThat(overGap).isEmpty();
        assertThat(moves).isEmpty();
    }

    @Test
    @DisplayName("다른 창 왼쪽 띠에 놓으면 move(끈 창, Side(대상, LEFT)) 를 한 번 부르고 미리보기를 지운다")
    void releaseMovesOnce() {
        // given
        LayoutView view = view();
        send(leftHandle, MouseEvent.MOUSE_PRESSED, 100, 10);
        send(leftHandle, MouseEvent.MOUSE_DRAGGED, 200, 100);
        send(leftHandle, MouseEvent.MOUSE_DRAGGED, 320, 200);

        // when
        send(leftHandle, MouseEvent.MOUSE_RELEASED, 320, 200);
        send(leftHandle, MouseEvent.MOUSE_RELEASED, 320, 200);

        // then
        assertThat(moves).containsExactly(new Move(P1, new DropZone.Side(P2, Edge.LEFT)));
        assertThat(view.draggingPane()).isFalse();
        assertThat(view.dropPreview()).isEmpty();
    }

    @Test
    @DisplayName("가운데에 놓으면 교환(Center) — 오른쪽 창의 손잡이 좌표도 창 영역 좌표로 바꿔 판정한다")
    void centerSwapsFromOtherHandle() {
        // given: p2 의 손잡이는 x 306 에서 시작한다 — 손잡이 (−206, 190) = 영역 (100, 190) 은 p1 의 가운데
        view();
        send(rightHandle, MouseEvent.MOUSE_PRESSED, 50, 10);

        // when
        send(rightHandle, MouseEvent.MOUSE_DRAGGED, -206, 190);
        send(rightHandle, MouseEvent.MOUSE_RELEASED, -206, 190);

        // then
        assertThat(moves).containsExactly(new Move(P2, new DropZone.Center(P1)));
    }

    @Test
    @DisplayName("Esc 로 끌기를 취소하면 미리보기를 지우고 놓아도 아무 일이 없다")
    void escapeCancels() {
        // given
        LayoutView view = view();
        send(leftHandle, MouseEvent.MOUSE_PRESSED, 100, 10);
        send(leftHandle, MouseEvent.MOUSE_DRAGGED, 320, 200);

        // when
        boolean cancelled = view.cancelDrag();
        send(leftHandle, MouseEvent.MOUSE_DRAGGED, 330, 200);
        send(leftHandle, MouseEvent.MOUSE_RELEASED, 330, 200);

        // then
        assertThat(cancelled).isTrue();
        assertThat(view.dropPreview()).isEmpty();
        assertThat(moves).isEmpty();
        assertThat(view.cancelDrag()).as("끄는 중이 아니면 Esc 를 가로채지 않는다").isFalse();
    }

    @Test
    @DisplayName("줌한 동안에는 머리줄을 끌 수 없다")
    void zoomedDisablesPaneDrag() {
        // given
        LayoutView view = view();
        show(view, Optional.of(P1));

        // when
        send(leftHandle, MouseEvent.MOUSE_PRESSED, 100, 10);
        send(leftHandle, MouseEvent.MOUSE_DRAGGED, 500, 200);
        send(leftHandle, MouseEvent.MOUSE_RELEASED, 500, 200);

        // then
        assertThat(view.draggingPane()).isFalse();
        assertThat(moves).isEmpty();
    }

    @Test
    @DisplayName("끄는 중에 포커스만 바뀌어 같은 창들로 다시 그려도 끌기는 이어진다 — 누르면 그 창에 포커스가 가기 때문")
    void redrawWithSamePanesKeepsDrag() {
        // given
        LayoutView view = view();
        send(leftHandle, MouseEvent.MOUSE_PRESSED, 100, 10);
        send(leftHandle, MouseEvent.MOUSE_DRAGGED, 320, 200);

        // when
        show(view, Optional.empty());
        send(leftHandle, MouseEvent.MOUSE_DRAGGED, 456, 200);
        send(leftHandle, MouseEvent.MOUSE_RELEASED, 456, 200);

        // then
        assertThat(moves).containsExactly(new Move(P1, new DropZone.Center(P2)));
    }

    @Test
    @DisplayName("끄는 창이 그사이 사라지면 끌기를 버린다")
    void draggedPaneRemovedCancels() {
        // given
        LayoutView view = view();
        send(leftHandle, MouseEvent.MOUSE_PRESSED, 100, 10);
        send(leftHandle, MouseEvent.MOUSE_DRAGGED, 320, 200);

        // when
        view.show(new LayoutNode.Leaf(P2), Map.of(P2, right));
        send(leftHandle, MouseEvent.MOUSE_RELEASED, 320, 200);

        // then
        assertThat(view.draggingPane()).isFalse();
        assertThat(moves).isEmpty();
    }

    @Test
    @DisplayName("폴더를 창 위로 끌면 그 창의 놓을 곳을 보이고, 놓으면 그 경로 · 자리로 한 번 연다")
    void folderHoverAndDrop() {
        // given
        LayoutView view = view();

        // when: p1 의 아래 띠
        boolean accepted = view.hoverFolder(150, 390);
        Optional<Rect> shown = view.dropPreview();
        boolean dropped = view.dropFolder(WORK, 150, 390);

        // then
        assertThat(accepted).isTrue();
        assertThat(shown).contains(new Rect(0, 203, 300, 197));
        assertThat(dropped).isTrue();
        assertThat(opens).containsExactly(new Open(WORK, new DropZone.Side(P1, Edge.BOTTOM)));
        assertThat(view.dropPreview()).isEmpty();
    }

    @Test
    @DisplayName("폴더를 간격에 놓으면 받지 않고, 창 영역을 벗어나면 미리보기를 지운다")
    void folderOutsideOrLeaving() {
        // given
        LayoutView view = view();

        // when
        boolean overGap = view.hoverFolder(303, 200);
        boolean droppedOnGap = view.dropFolder(WORK, 303, 200);
        view.hoverFolder(150, 200);
        view.endFolderHover();

        // then
        assertThat(overGap).isFalse();
        assertThat(droppedOnGap).isFalse();
        assertThat(opens).isEmpty();
        assertThat(view.dropPreview()).isEmpty();
    }

    @Test
    @DisplayName("줌한 동안 폴더를 놓으면 줌한 창이 영역을 채운 모양 그대로 판정한다")
    void folderWhileZoomed() {
        // given
        LayoutView view = view();
        show(view, Optional.of(P2));

        // when: 줌한 p2 의 왼쪽 띠(100 / 606)
        view.hoverFolder(100, 200);
        Optional<Rect> shown = view.dropPreview();
        view.dropFolder(WORK, 100, 200);

        // then
        assertThat(shown).contains(new Rect(0, 0, 300, 400));
        assertThat(opens).containsExactly(new Open(WORK, new DropZone.Side(P2, Edge.LEFT)));
    }
}
