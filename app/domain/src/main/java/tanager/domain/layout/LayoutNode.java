package tanager.domain.layout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;

/**
 * 탭 하나의 분할 레이아웃(불변 트리, SDD §5.3, ADR-0002). 바꾸는 연산은 새 트리를 돌려준다. 불변식: 창 ID 는 트리 안에서 유일하고 모든
 * 비율은 0.1 ~ 0.9 다({@link Ratio}). M4 의 첫 단계로 split · close 만 있다 — 크기 · 교환 · 이동은 뒤따른다.
 */
public sealed interface LayoutNode {

    /** 창 하나. */
    record Leaf(PaneId pane) implements LayoutNode {
        public Leaf {
            Objects.requireNonNull(pane, "pane");
        }
    }

    /** 둘로 나눈 영역. {@code first} 는 왼쪽(ROW) · 위(COLUMN). */
    record Split(Orientation orientation, Ratio ratio, LayoutNode first, LayoutNode second) implements LayoutNode {
        public Split {
            Objects.requireNonNull(orientation, "orientation");
            Objects.requireNonNull(ratio, "ratio");
            Objects.requireNonNull(first, "first");
            Objects.requireNonNull(second, "second");
            Set<PaneId> seen = new HashSet<>(first.panes());
            for (PaneId pane : second.panes()) {
                if (!seen.add(pane)) {
                    throw new IllegalArgumentException("같은 창이 두 번 들어 있다: " + pane);
                }
            }
        }
    }

    /** 창들(왼쪽 → 오른쪽, 위 → 아래 순서). 표시 번호 · 포커스 이동의 순서다. */
    default List<PaneId> panes() {
        List<PaneId> panes = new ArrayList<>();
        collect(this, panes);
        return List.copyOf(panes);
    }

    /**
     * {@code target} 창을 {@code orientation} 으로 반반 나눈다. {@code target} 은 first(왼쪽 · 위), {@code created} 는
     * second(오른쪽 · 아래)가 된다.
     */
    default Result<LayoutNode, LayoutError> split(PaneId target, Orientation orientation, PaneId created) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(orientation, "orientation");
        Objects.requireNonNull(created, "created");
        List<PaneId> panes = panes();
        if (!panes.contains(target)) {
            return Result.err(new LayoutError.UnknownPane(target));
        }
        if (panes.contains(created)) {
            return Result.err(new LayoutError.DuplicatePane(created));
        }
        return Result.ok(replaced(this, target, orientation, created));
    }

    /** {@code target} 창을 닫는다. 그 부모 Split 은 남은 형제로 줄고, 다른 비율은 그대로다. */
    default Result<LayoutNode, LayoutError> close(PaneId target) {
        Objects.requireNonNull(target, "target");
        if (!panes().contains(target)) {
            return Result.err(new LayoutError.UnknownPane(target));
        }
        return without(this, target)
                .<Result<LayoutNode, LayoutError>>map(Result::ok)
                .orElseGet(() -> Result.err(new LayoutError.LastPane(target)));
    }

    private static void collect(LayoutNode node, List<PaneId> out) {
        switch (node) {
            case Leaf leaf -> out.add(leaf.pane());
            case Split split -> {
                collect(split.first(), out);
                collect(split.second(), out);
            }
        }
    }

    private static LayoutNode replaced(LayoutNode node, PaneId target, Orientation orientation, PaneId created) {
        return switch (node) {
            case Leaf leaf ->
                leaf.pane().equals(target) ? new Split(orientation, Ratio.HALF, leaf, new Leaf(created)) : leaf;
            case Split split ->
                new Split(
                        split.orientation(),
                        split.ratio(),
                        replaced(split.first(), target, orientation, created),
                        replaced(split.second(), target, orientation, created));
        };
    }

    /** {@code target} 을 뺀 트리. 빼고 나면 아무것도 남지 않으면(그 창 하나뿐) 비어 있다. */
    private static Optional<LayoutNode> without(LayoutNode node, PaneId target) {
        return switch (node) {
            case Leaf leaf -> leaf.pane().equals(target) ? Optional.empty() : Optional.of(leaf);
            case Split split -> {
                Optional<LayoutNode> first = without(split.first(), target);
                Optional<LayoutNode> second = without(split.second(), target);
                if (first.isEmpty()) {
                    yield second;
                }
                if (second.isEmpty()) {
                    yield first;
                }
                yield Optional.of(new Split(split.orientation(), split.ratio(), first.get(), second.get()));
            }
        };
    }
}
