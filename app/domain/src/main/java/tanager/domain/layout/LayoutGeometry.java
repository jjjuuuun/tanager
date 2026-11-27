package tanager.domain.layout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tanager.domain.shared.PaneId;

/** 레이아웃 트리의 기하 계산(순수 함수, SDD §5.3). 그리기 · 마우스 판정 · 방향 포커스 이동이 같은 계산을 쓴다. */
public final class LayoutGeometry {
    /** 창 가장자리에서 이 몫 안쪽까지가 그 변에 붙이는 띠다. 나머지 가운데는 교환. */
    private static final double SIDE_BAND = 0.25;

    private LayoutGeometry() {}

    /**
     * 창마다 차지할 사각형({@code panes()} 순서). Split 은 간격 {@code gap} 을 뺀 길이를 비율대로 나눈다. 영역이 간격보다 작으면 창 크기는 0 이
     * 될 수 있지만 음수 · 영역 밖은 없다.
     */
    public static Map<PaneId, Rect> rects(LayoutNode tree, Rect bounds, int gap) {
        requireLayout(tree, bounds, gap);
        Map<PaneId, Rect> out = new LinkedHashMap<>();
        place(tree, bounds, gap, out);
        return out;
    }

    /**
     * 방향 포커스 이동(FR-LAY-06). {@code from} 에서 {@code direction} 쪽에 온전히 놓인 창 중 수직 축으로 가장 많이 겹치는 창 — 같으면 더
     * 가까운 창, 그래도 같으면 위 · 왼쪽 창. 모서리만 닿는 창은 고르지 않는다. 그런 창이 없거나 {@code from} 이 트리에 없으면 비어 있다.
     */
    public static Optional<PaneId> neighbor(LayoutNode tree, PaneId from, Direction direction, Rect bounds, int gap) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(direction, "direction");
        Map<PaneId, Rect> rects = rects(tree, bounds, gap);
        Optional<Rect> origin = Optional.ofNullable(rects.get(from));
        if (origin.isEmpty()) {
            return Optional.empty();
        }
        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<PaneId, Rect> entry : rects.entrySet()) {
            Rect rect = entry.getValue();
            int distance = distance(origin.get(), rect, direction);
            int overlap = overlap(origin.get(), rect, direction.axis());
            if (!entry.getKey().equals(from) && distance >= 0 && overlap > 0) {
                candidates.add(new Candidate(entry.getKey(), rect, overlap, distance));
            }
        }
        return candidates.stream().min(Candidate.ORDER).map(Candidate::pane);
    }

    /**
     * 경계선(FR-LAY-11). Split 마다 하나, 루트부터 깊이 우선(first 먼저) 순서. 잡는 사각형은 간격 가운데에 놓인 폭 {@code hitPx} 의 띠로 Split
     * 영역을 가로지른다 — 간격이 좁아도 잡을 수 있게 간격보다 넓을 수 있다.
     */
    public static List<Divider> dividers(LayoutNode tree, Rect bounds, int gap, int hitPx) {
        requireLayout(tree, bounds, gap);
        if (hitPx < 1) {
            throw new IllegalArgumentException("잡는 폭은 1 이상이다: " + hitPx);
        }
        List<Divider> out = new ArrayList<>();
        collectDividers(tree, bounds, gap, hitPx, SplitPath.ROOT, out);
        return List.copyOf(out);
    }

    /**
     * 끌어다 놓을 곳(FR-LAY-12). 점이 있는 창의 가장자리 25% 띠면 가장 가까운 변의 {@link DropZone.Side}, 그 안쪽이면 {@link
     * DropZone.Center}. 창 사각형은 겹치지 않으므로 창 안의 점마다 정확히 하나다. 간격 · 영역 밖처럼 어느 창에도 없는 점이면 비어 있다.
     */
    public static Optional<DropZone> dropZone(LayoutNode tree, Rect bounds, int gap, int x, int y) {
        for (Map.Entry<PaneId, Rect> entry : rects(tree, bounds, gap).entrySet()) {
            if (entry.getValue().contains(x, y)) {
                return Optional.of(zoneIn(entry.getKey(), entry.getValue(), x, y));
            }
        }
        return Optional.empty();
    }

    /**
     * 경계선을 점 ({@code x}, {@code y}) 까지 끌었을 때의 비율(FR-LAY-11). 점을 간격 가운데로 보고, 그 비율로 {@link #rects} 를 그리면 first
     * 가 {@code minFirstPx}, second 가 {@code minSecondPx} 이상이 되게 고정한다(창 최소 20열 × 5행을 UI 가 픽셀로 바꿔 넘긴다).
     * 비율은 0.1 ~ 0.9 안이다. 둘 다 지킬 수 없을 만큼 좁으면 최소 크기의 비로 나누고, 나눌 길이가 없으면 반반이다.
     */
    public static Ratio ratioAt(Divider divider, int x, int y, int minFirstPx, int minSecondPx) {
        Objects.requireNonNull(divider, "divider");
        if (minFirstPx < 0 || minSecondPx < 0) {
            throw new IllegalArgumentException("최소 크기는 음수일 수 없다: " + minFirstPx + ", " + minSecondPx);
        }
        Rect area = divider.area();
        boolean row = divider.orientation() == Orientation.ROW;
        int length = row ? area.width() : area.height();
        int usedGap = Math.min(divider.gap(), length);
        int available = length - usedGap;
        if (available == 0) {
            return Ratio.HALF;
        }
        // 픽셀로 고정한 뒤 비율로 바꾼다 — rects 의 반올림과 같은 기준이어야 최소 크기가 지켜진다
        double low = Math.max(Ratio.MIN * available, minFirstPx);
        double high = Math.min(Ratio.MAX * available, available - minSecondPx);
        if (low > high) {
            return clamped((double) minFirstPx / (minFirstPx + minSecondPx));
        }
        double first = (row ? x - area.x() : y - area.y()) - usedGap / 2.0;
        return clamped(Math.clamp(first, low, high) / available);
    }

    private static Ratio clamped(double value) {
        return new Ratio(Math.clamp(value, Ratio.MIN, Ratio.MAX));
    }

    private static void requireLayout(LayoutNode tree, Rect bounds, int gap) {
        Objects.requireNonNull(tree, "tree");
        Objects.requireNonNull(bounds, "bounds");
        if (gap < 0) {
            throw new IllegalArgumentException("간격은 음수일 수 없다: " + gap);
        }
    }

    /** Split 하나가 영역을 나눈 결과. {@code gapStart} 는 나눈 축의 간격 시작 좌표, {@code usedGap} 은 실제 간격(영역보다 클 수 없다). */
    private record Halves(Rect first, Rect second, int gapStart, int usedGap) {}

    private static Halves halves(LayoutNode.Split split, Rect area, int gap) {
        boolean row = split.orientation() == Orientation.ROW;
        int length = row ? area.width() : area.height();
        int usedGap = Math.min(gap, length);
        int available = length - usedGap;
        int first = (int) Math.round(available * split.ratio().value());
        int second = available - first;
        if (row) {
            return new Halves(
                    new Rect(area.x(), area.y(), first, area.height()),
                    new Rect(area.x() + first + usedGap, area.y(), second, area.height()),
                    area.x() + first,
                    usedGap);
        }
        return new Halves(
                new Rect(area.x(), area.y(), area.width(), first),
                new Rect(area.x(), area.y() + first + usedGap, area.width(), second),
                area.y() + first,
                usedGap);
    }

    private static void place(LayoutNode node, Rect area, int gap, Map<PaneId, Rect> out) {
        switch (node) {
            case LayoutNode.Leaf leaf -> out.put(leaf.pane(), area);
            case LayoutNode.Split split -> {
                Halves halves = halves(split, area, gap);
                place(split.first(), halves.first(), gap, out);
                place(split.second(), halves.second(), gap, out);
            }
        }
    }

    private static void collectDividers(
            LayoutNode node, Rect area, int gap, int hitPx, SplitPath path, List<Divider> out) {
        switch (node) {
            case LayoutNode.Leaf _ -> {}
            case LayoutNode.Split split -> {
                Halves halves = halves(split, area, gap);
                // 간격 가운데(gapStart + usedGap / 2)에 띠의 가운데를 맞춘다. 반 픽셀은 위 · 왼쪽으로
                int hitStart = Math.floorDiv(2 * halves.gapStart() + halves.usedGap() - hitPx, 2);
                Rect hit = split.orientation() == Orientation.ROW
                        ? new Rect(hitStart, area.y(), hitPx, area.height())
                        : new Rect(area.x(), hitStart, area.width(), hitPx);
                out.add(new Divider(path, split.orientation(), hit, area, gap));
                collectDividers(split.first(), halves.first(), gap, hitPx, path.then(SplitPath.Branch.FIRST), out);
                collectDividers(split.second(), halves.second(), gap, hitPx, path.then(SplitPath.Branch.SECOND), out);
            }
        }
    }

    /** {@code rect} 가 {@code origin} 의 {@code direction} 쪽으로 떨어진 거리. 그쪽에 온전히 놓이지 않았으면 음수. */
    private static int distance(Rect origin, Rect rect, Direction direction) {
        return switch (direction) {
            case RIGHT -> rect.x() - (origin.x() + origin.width());
            case LEFT -> origin.x() - (rect.x() + rect.width());
            case DOWN -> rect.y() - (origin.y() + origin.height());
            case UP -> origin.y() - (rect.y() + rect.height());
        };
    }

    /** 이동 축에 수직인 축으로 겹치는 길이. 좌우 이동이면 세로 겹침이다. */
    private static int overlap(Rect a, Rect b, Orientation axis) {
        return switch (axis) {
            case ROW -> Math.min(a.y() + a.height(), b.y() + b.height()) - Math.max(a.y(), b.y());
            case COLUMN -> Math.min(a.x() + a.width(), b.x() + b.width()) - Math.max(a.x(), b.x());
        };
    }

    private record Candidate(PaneId pane, Rect rect, int overlap, int distance) {
        static final Comparator<Candidate> ORDER = Comparator.comparingInt((Candidate c) -> -c.overlap())
                .thenComparingInt(Candidate::distance)
                .thenComparingInt(c -> c.rect().y())
                .thenComparingInt(c -> c.rect().x());
    }

    private static DropZone zoneIn(PaneId pane, Rect rect, int x, int y) {
        // 픽셀 가운데 기준의 상대 위치(0 ~ 1). 창이 납작해도 변마다 자기 길이의 25% 띠를 갖는다
        double fx = (x - rect.x() + 0.5) / rect.width();
        double fy = (y - rect.y() + 0.5) / rect.height();
        Edge nearest = Edge.TOP;
        double best = Double.POSITIVE_INFINITY;
        for (Edge edge : Edge.values()) {
            double d = switch (edge) {
                case TOP -> fy;
                case BOTTOM -> 1 - fy;
                case LEFT -> fx;
                case RIGHT -> 1 - fx;
            };
            if (d < best) {
                best = d;
                nearest = edge;
            }
        }
        return best < SIDE_BAND ? new DropZone.Side(pane, nearest) : new DropZone.Center(pane);
    }
}
