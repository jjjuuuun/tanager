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

/** 아무 트리 · 영역에서도 창 사각형은 영역 안에 있고 서로 겹치지 않는다(#89). */
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

    private static boolean overlaps(Rect a, Rect b) {
        return a.x() < b.x() + b.width()
                && b.x() < a.x() + a.width()
                && a.y() < b.y() + b.height()
                && b.y() < a.y() + a.height();
    }
}
