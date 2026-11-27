package tanager.ui.layout;

import java.util.Objects;
import java.util.Optional;
import tanager.domain.layout.DropZone;
import tanager.domain.layout.Edge;
import tanager.domain.layout.LayoutGeometry;
import tanager.domain.layout.LayoutNode;
import tanager.domain.layout.Orientation;
import tanager.domain.layout.Rect;
import tanager.domain.shared.PaneId;

/**
 * 창 · 폴더 끌어다 놓기의 순수 판정(#151, FR-LAY-12 · 13). 놓을 곳은 domain {@link LayoutGeometry#dropZone} 이 정하고, 여기서는 끌기 시작 ·
 * 끄는 창 제외 · 미리보기 사각형만 계산한다. Swing 없이 시험한다.
 */
final class PaneDrags {
    /** 끌기로 보는 최소 이동(px). 이보다 덜 움직이면 클릭 · 더블클릭(포커스 · 줌)이다. */
    static final int THRESHOLD_PX = 4;

    private PaneDrags() {}

    /** 머리줄을 누른 창 하나. 누른 채로 문턱을 넘으면 끄는 중이 된다. */
    sealed interface Grab {
        /** 끄는 창. */
        PaneId pane();

        /** 눌렀지만 아직 문턱을 넘지 않았다. ({@code x}, {@code y}) 는 누른 곳(창 영역 좌표). */
        record Pressed(PaneId pane, int x, int y) implements Grab {
            public Pressed {
                Objects.requireNonNull(pane, "pane");
            }
        }

        /** 끄는 중. {@code zone} 은 지금 점에서 놓을 곳 — 자기 위 · 간격 · 영역 밖이면 비어 있다. */
        record Dragging(PaneId pane, Optional<DropZone> zone) implements Grab {
            public Dragging {
                Objects.requireNonNull(pane, "pane");
                Objects.requireNonNull(zone, "zone");
            }
        }
    }

    /** 누른 곳에서 문턱 이상(거리 {@value #THRESHOLD_PX}px) 움직였는가. */
    static boolean started(int fromX, int fromY, int x, int y) {
        long dx = x - fromX;
        long dy = y - fromY;
        return dx * dx + dy * dy >= (long) THRESHOLD_PX * THRESHOLD_PX;
    }

    /** 끄는 창 {@code dragged} 를 점에 놓을 곳. 다른 창 위에서만 있다 — 자기 위에 놓는 것은 옮기기가 아니다. */
    static Optional<DropZone> paneZone(LayoutNode tree, Rect area, int gap, PaneId dragged, int x, int y) {
        Objects.requireNonNull(dragged, "dragged");
        return LayoutGeometry.dropZone(tree, area, gap, x, y)
                .filter(zone -> !zone.target().equals(dragged));
    }

    /** 폴더를 점에 놓을 곳. 줌한 동안에는 줌한 창이 영역을 혼자 채운 모양(화면에 보이는 그대로)으로 판정한다. */
    static Optional<DropZone> folderZone(LayoutNode tree, Optional<PaneId> zoomed, Rect area, int gap, int x, int y) {
        return LayoutGeometry.dropZone(shown(tree, zoomed), area, gap, x, y);
    }

    /** 점이 ({@code x}, {@code y}) 로 움직였을 때의 다음 상태. */
    static Grab moved(Grab grab, LayoutNode tree, Rect area, int gap, int x, int y) {
        return switch (grab) {
            case Grab.Pressed pressed ->
                started(pressed.x(), pressed.y(), x, y)
                        ? new Grab.Dragging(pressed.pane(), paneZone(tree, area, gap, pressed.pane(), x, y))
                        : pressed;
            case Grab.Dragging dragging ->
                new Grab.Dragging(dragging.pane(), paneZone(tree, area, gap, dragging.pane(), x, y));
        };
    }

    /** 놓았을 때 옮길 곳. 끄는 중이고 놓을 곳이 있을 때만 있다. */
    static Optional<DropZone> released(Grab grab) {
        return switch (grab) {
            case Grab.Pressed _ -> Optional.empty();
            case Grab.Dragging dragging -> dragging.zone();
        };
    }

    /**
     * 놓을 곳을 보일 사각형. 변이면 놓은 뒤 그 창이 차지할 대상 창의 반쪽(간격 제외, 옮기기는 반반으로 나눈다), 가운데(교환)면 대상 창 전체. 대상이
     * 보이는 트리에 없으면 비어 있다.
     */
    static Optional<Rect> highlight(LayoutNode tree, Optional<PaneId> zoomed, Rect area, int gap, DropZone zone) {
        Optional<Rect> target = Optional.ofNullable(
                LayoutGeometry.rects(shown(tree, zoomed), area, gap).get(zone.target()));
        return target.map(rect -> switch (zone) {
            case DropZone.Center _ -> rect;
            case DropZone.Side side -> half(rect, side.edge(), gap);
        });
    }

    // LayoutGeometry.rects 가 반반 Split 을 나누는 것과 같은 반올림
    private static Rect half(Rect rect, Edge edge, int gap) {
        boolean row = edge.orientation() == Orientation.ROW;
        int length = row ? rect.width() : rect.height();
        int usedGap = Math.min(gap, length);
        int first = (int) Math.round((length - usedGap) * 0.5);
        int second = length - usedGap - first;
        int offset = edge.leading() ? 0 : first + usedGap;
        int size = edge.leading() ? first : second;
        return row
                ? new Rect(rect.x() + offset, rect.y(), size, rect.height())
                : new Rect(rect.x(), rect.y() + offset, rect.width(), size);
    }

    // 줌한 동안 화면에 보이는 배치 — 줌한 창 하나가 영역을 채운다
    private static LayoutNode shown(LayoutNode tree, Optional<PaneId> zoomed) {
        return zoomed.<LayoutNode>map(LayoutNode.Leaf::new).orElse(tree);
    }
}
