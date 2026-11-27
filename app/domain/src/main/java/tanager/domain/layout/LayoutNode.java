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
 * 비율은 0.1 ~ 0.9 다({@link Ratio}). 연산은 나누기 · 닫기 · 크기(키보드 · 드래그) · 교환 · 이동이다.
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

    /**
     * 키보드 크기 조절(FR-LAY-02). {@code target} 을 감싼 가장 가까운 {@code direction} 축의 Split 경계를 그 방향으로 {@code delta}
     * 만큼 옮긴다 — RIGHT · DOWN 은 비율을 키우고 LEFT · UP 은 줄인다. 어느 쪽 창에서 눌러도 경계가 같은 쪽으로 가야 tmux 처럼 예측할 수 있다.
     * 비율은 0.1 ~ 0.9 로 고정한다.
     *
     * @param delta 옮길 몫(0 이상, 예: 0.05)
     */
    default Result<LayoutNode, LayoutError> resize(PaneId target, Direction direction, double delta) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(direction, "direction");
        if (!Double.isFinite(delta) || delta < 0) {
            throw new IllegalArgumentException("옮길 몫은 0 이상의 유한한 수다: " + delta);
        }
        if (!panes().contains(target)) {
            return Result.err(new LayoutError.UnknownPane(target));
        }
        double signed = direction.forward() ? delta : -delta;
        return resized(this, target, direction.axis(), signed)
                .<Result<LayoutNode, LayoutError>>map(Result::ok)
                .orElseGet(() -> Result.err(new LayoutError.NoSplitInDirection(target, direction)));
    }

    /** 경계선 드래그(FR-LAY-11). {@code path} 가 가리키는 Split 의 비율을 {@code ratio} 로 바꾼다. */
    default Result<LayoutNode, LayoutError> resizeTo(SplitPath path, Ratio ratio) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(ratio, "ratio");
        return withRatioAt(this, path.branches(), 0, ratio)
                .<Result<LayoutNode, LayoutError>>map(Result::ok)
                .orElseGet(() -> Result.err(new LayoutError.UnknownSplit(path)));
    }

    /** 두 창의 자리를 바꾼다(FR-LAY-03). 트리 모양 · 비율은 그대로다. */
    default Result<LayoutNode, LayoutError> swap(PaneId a, PaneId b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        return checkPair(a, b).map(_ -> swapped(this, a, b));
    }

    /**
     * 창 옮기기(FR-LAY-12). {@code moved} 를 닫고, {@code target} 을 반반으로 나눠 {@code edge} 쪽에 붙인다 — 위 · 왼쪽 변이면
     * {@code moved} 가 first, 아래 · 오른쪽 변이면 second 다. 창 집합은 그대로다.
     */
    default Result<LayoutNode, LayoutError> move(PaneId moved, PaneId target, Edge edge) {
        Objects.requireNonNull(moved, "moved");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(edge, "edge");
        // 두 창이 다르고 모두 있으면 창이 둘 이상이라 close 는 실패하지 않는다
        return checkPair(moved, target).flatMap(_ -> close(moved)).map(rest -> attached(rest, target, moved, edge));
    }

    private Result<LayoutNode, LayoutError> checkPair(PaneId a, PaneId b) {
        if (a.equals(b)) {
            return Result.err(new LayoutError.SamePane(a));
        }
        List<PaneId> panes = panes();
        for (PaneId pane : List.of(a, b)) {
            if (!panes.contains(pane)) {
                return Result.err(new LayoutError.UnknownPane(pane));
            }
        }
        return Result.ok(this);
    }

    /**
     * 경계를 옮긴 트리. {@code target} 은 이 부분 트리 안에 있다. 그 위의 같은 축 Split 이 이 부분 트리 안에 없으면 비어 있다 — 깊은 쪽부터
     * 찾아야 "가장 가까운" Split 이 바뀐다.
     */
    private static Optional<LayoutNode> resized(LayoutNode node, PaneId target, Orientation axis, double signed) {
        return switch (node) {
            case Leaf _ -> Optional.empty();
            case Split split -> {
                boolean inFirst = split.first().panes().contains(target);
                LayoutNode child = inFirst ? split.first() : split.second();
                Optional<LayoutNode> deeper = resized(child, target, axis, signed);
                if (deeper.isPresent()) {
                    yield deeper.map(c -> inFirst
                            ? new Split(split.orientation(), split.ratio(), c, split.second())
                            : new Split(split.orientation(), split.ratio(), split.first(), c));
                }
                if (split.orientation() != axis) {
                    yield Optional.empty();
                }
                double value = Math.clamp(split.ratio().value() + signed, Ratio.MIN, Ratio.MAX);
                yield Optional.of(new Split(split.orientation(), new Ratio(value), split.first(), split.second()));
            }
        };
    }

    private static Optional<LayoutNode> withRatioAt(
            LayoutNode node, List<SplitPath.Branch> branches, int depth, Ratio ratio) {
        return switch (node) {
            case Leaf _ -> Optional.empty();
            case Split split -> {
                if (depth == branches.size()) {
                    yield Optional.of(new Split(split.orientation(), ratio, split.first(), split.second()));
                }
                yield switch (branches.get(depth)) {
                    case FIRST ->
                        withRatioAt(split.first(), branches, depth + 1, ratio)
                                .map(c -> new Split(split.orientation(), split.ratio(), c, split.second()));
                    case SECOND ->
                        withRatioAt(split.second(), branches, depth + 1, ratio)
                                .map(c -> new Split(split.orientation(), split.ratio(), split.first(), c));
                };
            }
        };
    }

    private static LayoutNode swapped(LayoutNode node, PaneId a, PaneId b) {
        return switch (node) {
            case Leaf leaf -> {
                if (leaf.pane().equals(a)) {
                    yield new Leaf(b);
                }
                yield leaf.pane().equals(b) ? new Leaf(a) : leaf;
            }
            case Split split ->
                new Split(
                        split.orientation(),
                        split.ratio(),
                        swapped(split.first(), a, b),
                        swapped(split.second(), a, b));
        };
    }

    private static LayoutNode attached(LayoutNode node, PaneId target, PaneId moved, Edge edge) {
        return switch (node) {
            case Leaf leaf -> {
                if (!leaf.pane().equals(target)) {
                    yield leaf;
                }
                Leaf added = new Leaf(moved);
                yield edge.leading()
                        ? new Split(edge.orientation(), Ratio.HALF, added, leaf)
                        : new Split(edge.orientation(), Ratio.HALF, leaf, added);
            }
            case Split split ->
                new Split(
                        split.orientation(),
                        split.ratio(),
                        attached(split.first(), target, moved, edge),
                        attached(split.second(), target, moved, edge));
        };
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
