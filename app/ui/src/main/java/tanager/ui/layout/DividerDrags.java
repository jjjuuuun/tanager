package tanager.ui.layout;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tanager.domain.layout.Divider;
import tanager.domain.layout.LayoutNode;
import tanager.domain.layout.Orientation;
import tanager.domain.layout.Ratio;
import tanager.domain.layout.Rect;
import tanager.domain.layout.SplitPath;

/** 경계선 드래그의 순수 판정(#150, FR-LAY-11). Swing 없이 시험한다. */
final class DividerDrags {
    private DividerDrags() {}

    /** 한 경계선의 first · second 쪽이 가져야 할 최소 길이(픽셀, 나누는 축 방향). */
    record Minimums(int first, int second) {
        Minimums {
            if (first < 0 || second < 0) {
                throw new IllegalArgumentException("최소 길이는 음수일 수 없다: " + first + ", " + second);
            }
        }
    }

    /**
     * 점 아래의 경계선. 띠가 겹치는 T 자 교차점에서는 먼저 나온 쪽(루트에 가까운, 더 긴 경계선)을 고른다 — {@link
     * tanager.domain.layout.LayoutGeometry#dividers} 가 루트부터 내놓는다.
     */
    static Optional<Divider> at(List<Divider> dividers, int x, int y) {
        for (Divider divider : dividers) {
            if (divider.hit().contains(x, y)) {
                return Optional.of(divider);
            }
        }
        return Optional.empty();
    }

    /** {@code ratio} 로 그렸을 때 간격이 올 자리 — 미리보기 선. {@code LayoutGeometry.rects} 와 같은 반올림이다. 간격이 0 이면 1px. */
    static Rect preview(Divider divider, Ratio ratio) {
        Rect area = divider.area();
        boolean row = divider.orientation() == Orientation.ROW;
        int length = row ? area.width() : area.height();
        int usedGap = Math.min(divider.gap(), length);
        int first = (int) Math.round((length - usedGap) * ratio.value());
        int thickness = Math.max(1, usedGap);
        return row
                ? new Rect(area.x() + first, area.y(), thickness, area.height())
                : new Rect(area.x(), area.y() + first, area.width(), thickness);
    }

    /**
     * 경계선 양쪽의 최소 길이. 창 하나의 최소 크기를 그 하위 트리에서 나누는 축으로 늘어선 창 수만큼(사이 간격 포함) 쌓는다 — 바깥 경계선을 끌어도
     * 안쪽 창이 최소 크기 밑으로 줄지 않게. {@code divider} 가 {@code tree} 의 Split 을 가리키지 않으면 창 하나 기준이다.
     */
    static Minimums minimums(LayoutNode tree, Divider divider, PaneMinimum pane) {
        Objects.requireNonNull(pane, "pane");
        Orientation axis = divider.orientation();
        int paneLength = axis == Orientation.ROW ? pane.width() : pane.height();
        return splitAt(tree, divider.path())
                .map(split -> new Minimums(
                        along(split.first(), axis, paneLength, divider.gap()),
                        along(split.second(), axis, paneLength, divider.gap())))
                .orElse(new Minimums(paneLength, paneLength));
    }

    private static int along(LayoutNode node, Orientation axis, int paneLength, int gap) {
        return switch (node) {
            case LayoutNode.Leaf _ -> paneLength;
            case LayoutNode.Split split -> {
                int first = along(split.first(), axis, paneLength, gap);
                int second = along(split.second(), axis, paneLength, gap);
                yield split.orientation() == axis ? first + gap + second : Math.max(first, second);
            }
        };
    }

    private static Optional<LayoutNode.Split> splitAt(LayoutNode tree, SplitPath path) {
        LayoutNode node = tree;
        for (SplitPath.Branch branch : path.branches()) {
            if (!(node instanceof LayoutNode.Split split)) {
                return Optional.empty();
            }
            node = branch == SplitPath.Branch.FIRST ? split.first() : split.second();
        }
        return node instanceof LayoutNode.Split split ? Optional.of(split) : Optional.empty();
    }
}
