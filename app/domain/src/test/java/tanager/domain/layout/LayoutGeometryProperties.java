package tanager.domain.layout;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import tanager.domain.shared.PaneId;

/** 아무 트리 · 영역에서도 창 사각형은 영역 안에 있고 서로 겹치지 않는다(#89) · 놓을 곳 · 경계선 · 드래그 비율의 불변식(#146). */
class LayoutGeometryProperties {

    record Step(int index, Orientation orientation, double ratio) {}

    @Provide
    Arbitrary<List<Step>> steps() {
        return Combinators.combine(
                        Arbitraries.integers().between(0, 1000),
                        Arbitraries.of(Orientation.values()),
                        Arbitraries.doubles().between(Ratio.MIN, Ratio.MAX))
                .as(Step::new)
                .list()
                .ofMaxSize(12);
    }

    private static LayoutNode tree(List<Step> steps) {
        LayoutNode tree = new LayoutNode.Leaf(new PaneId("p0"));
        int next = 1;
        for (Step step : steps) {
            List<PaneId> panes = tree.panes();
            tree = tree.split(panes.get(step.index() % panes.size()), step.orientation(), new PaneId("p" + next++))
                    .value()
                    .orElseThrow();
        }
        return withRatios(tree, new ArrayList<>(steps));
    }

    // 나눈 뒤 비율을 바꿔 반반이 아닌 트리도 만든다
    private static LayoutNode withRatios(LayoutNode node, List<Step> steps) {
        return switch (node) {
            case LayoutNode.Leaf leaf -> leaf;
            case LayoutNode.Split split -> {
                Ratio ratio = steps.isEmpty()
                        ? split.ratio()
                        : new Ratio(steps.removeFirst().ratio());
                yield new LayoutNode.Split(
                        split.orientation(),
                        ratio,
                        withRatios(split.first(), steps),
                        withRatios(split.second(), steps));
            }
        };
    }

    @Property
    @Label("창마다 사각형이 하나씩 있고, 모두 영역 안이며, 서로 겹치지 않는다")
    void rectsTileWithinBounds(
            @ForAll("steps") List<Step> steps,
            @ForAll @IntRange(min = 0, max = 3000) int width,
            @ForAll @IntRange(min = 0, max = 2000) int height,
            @ForAll @IntRange(min = 0, max = 12) int gap) {
        // given
        LayoutNode tree = tree(steps);
        Rect bounds = new Rect(7, 9, width, height);

        // when
        Map<PaneId, Rect> rects = LayoutGeometry.rects(tree, bounds, gap);

        // then
        assertThat(rects.keySet()).containsExactlyElementsOf(tree.panes());
        List<Rect> all = List.copyOf(rects.values());
        for (Rect r : all) {
            assertThat(r.x()).isBetween(bounds.x(), bounds.x() + bounds.width());
            assertThat(r.y()).isBetween(bounds.y(), bounds.y() + bounds.height());
            assertThat(r.x() + r.width()).isLessThanOrEqualTo(bounds.x() + bounds.width());
            assertThat(r.y() + r.height()).isLessThanOrEqualTo(bounds.y() + bounds.height());
        }
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                assertThat(overlaps(all.get(i), all.get(j)))
                        .as(all.get(i) + " / " + all.get(j))
                        .isFalse();
            }
        }
    }

    @Property
    @Label("창 사각형 안의 점은 그 창을 대상으로 하는 놓을 곳이 정확히 하나, 창 밖의 점은 없다 — 간격 0 이면 영역 안의 모든 점에 있다")
    void dropZoneExactlyOneInsidePanes(
            @ForAll("steps") List<Step> steps,
            @ForAll @IntRange(min = 1, max = 3000) int width,
            @ForAll @IntRange(min = 1, max = 2000) int height,
            @ForAll @IntRange(min = 0, max = 12) int gap,
            @ForAll @IntRange(min = 0, max = 2999) int px,
            @ForAll @IntRange(min = 0, max = 1999) int py) {
        // given
        LayoutNode tree = tree(steps);
        Rect bounds = new Rect(7, 9, width, height);
        int x = bounds.x() + px % width;
        int y = bounds.y() + py % height;
        List<PaneId> under = new ArrayList<>();
        LayoutGeometry.rects(tree, bounds, gap).forEach((pane, rect) -> {
            if (rect.contains(x, y)) {
                under.add(pane);
            }
        });

        // when
        var zone = LayoutGeometry.dropZone(tree, bounds, gap, x, y);

        // then
        assertThat(under).hasSizeLessThanOrEqualTo(1);
        assertThat(zone.map(DropZone::target)).isEqualTo(under.stream().findFirst());
        if (gap == 0) {
            assertThat(zone).isPresent();
        }
    }

    @Property
    @Label("ratioAt 의 비율로 그리면 지킬 수 있는 최소 크기는 양쪽 모두 지킨다")
    void ratioAtKeepsFeasibleMinimums(
            @ForAll("steps") List<Step> steps,
            @ForAll @IntRange(min = 0, max = 3000) int width,
            @ForAll @IntRange(min = 0, max = 2000) int height,
            @ForAll @IntRange(min = 0, max = 12) int gap,
            @ForAll @IntRange(min = 0, max = 1000) int pick,
            @ForAll @IntRange(min = -100, max = 3100) int x,
            @ForAll @IntRange(min = -100, max = 2100) int y,
            @ForAll @IntRange(min = 0, max = 400) int minFirst,
            @ForAll @IntRange(min = 0, max = 400) int minSecond) {
        // given
        LayoutNode tree = tree(steps);
        Rect bounds = new Rect(0, 0, width, height);
        List<Divider> dividers = LayoutGeometry.dividers(tree, bounds, gap, 6);
        if (dividers.isEmpty()) {
            return;
        }
        Divider divider = dividers.get(pick % dividers.size());

        // when
        Ratio ratio = LayoutGeometry.ratioAt(divider, x, y, minFirst, minSecond);
        LayoutNode resized = tree.resizeTo(divider.path(), ratio).value().orElseThrow();

        // then: 그 Split 의 first · second 가 차지하는 길이
        boolean row = divider.orientation() == Orientation.ROW;
        int length = row ? divider.area().width() : divider.area().height();
        int available = length - Math.min(gap, length);
        int first = (int) Math.round(available * ratio.value());
        int second = available - first;
        boolean feasible =
                Math.max(Ratio.MIN * available, minFirst) <= Math.min(Ratio.MAX * available, available - minSecond);
        if (feasible) {
            assertThat(first).isGreaterThanOrEqualTo(minFirst);
            assertThat(second).isGreaterThanOrEqualTo(minSecond);
        }
        assertThat(LayoutGeometry.dividers(resized, bounds, gap, 6)).hasSameSizeAs(dividers);
    }

    @Property
    @Label("경계선은 Split 마다 하나이고, 잡는 사각형의 가운데가 그 Split 의 간격 가운데다")
    void dividerPerSplitCenteredOnGap(
            @ForAll("steps") List<Step> steps,
            @ForAll @IntRange(min = 0, max = 3000) int width,
            @ForAll @IntRange(min = 0, max = 2000) int height,
            @ForAll @IntRange(min = 0, max = 12) int gap) {
        // given
        LayoutNode tree = tree(steps);

        // when
        List<Divider> dividers = LayoutGeometry.dividers(tree, new Rect(3, 5, width, height), gap, 6);

        // then
        assertThat(dividers).hasSize(tree.panes().size() - 1);
        for (Divider d : dividers) {
            boolean row = d.orientation() == Orientation.ROW;
            assertThat(row ? d.hit().width() : d.hit().height()).isEqualTo(6);
            assertThat(row ? d.hit().height() : d.hit().width())
                    .isEqualTo(row ? d.area().height() : d.area().width());
        }
    }

    private static boolean overlaps(Rect a, Rect b) {
        return a.x() < b.x() + b.width()
                && b.x() < a.x() + a.width()
                && a.y() < b.y() + b.height()
                && b.y() < a.y() + a.height();
    }
}
