package tanager.domain.layout;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import tanager.domain.shared.PaneId;

/** 레이아웃 트리의 기하 계산(순수 함수, SDD §5.3). 그리기 · 마우스 판정 · 방향 포커스 이동이 같은 계산을 쓴다. */
public final class LayoutGeometry {
    private LayoutGeometry() {}

    /**
     * 창마다 차지할 사각형({@code panes()} 순서). Split 은 간격 {@code gap} 을 뺀 길이를 비율대로 나눈다. 영역이 간격보다 작으면 창 크기는 0 이
     * 될 수 있지만 음수 · 영역 밖은 없다.
     */
    public static Map<PaneId, Rect> rects(LayoutNode tree, Rect bounds, int gap) {
        Objects.requireNonNull(tree, "tree");
        Objects.requireNonNull(bounds, "bounds");
        if (gap < 0) {
            throw new IllegalArgumentException("간격은 음수일 수 없다: " + gap);
        }
        Map<PaneId, Rect> out = new LinkedHashMap<>();
        place(tree, bounds, gap, out);
        return out;
    }

    private static void place(LayoutNode node, Rect area, int gap, Map<PaneId, Rect> out) {
        switch (node) {
            case LayoutNode.Leaf leaf -> out.put(leaf.pane(), area);
            case LayoutNode.Split split -> {
                boolean row = split.orientation() == Orientation.ROW;
                int length = row ? area.width() : area.height();
                int usedGap = Math.min(gap, length);
                int available = length - usedGap;
                int first = (int) Math.round(available * split.ratio().value());
                int second = available - first;
                if (row) {
                    place(split.first(), new Rect(area.x(), area.y(), first, area.height()), gap, out);
                    place(
                            split.second(),
                            new Rect(area.x() + first + usedGap, area.y(), second, area.height()),
                            gap,
                            out);
                } else {
                    place(split.first(), new Rect(area.x(), area.y(), area.width(), first), gap, out);
                    place(
                            split.second(),
                            new Rect(area.x(), area.y() + first + usedGap, area.width(), second),
                            gap,
                            out);
                }
            }
        }
    }
}
