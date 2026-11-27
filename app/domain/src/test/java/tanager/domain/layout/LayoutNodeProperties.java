package tanager.domain.layout;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import tanager.domain.shared.PaneId;

/** split · close 를 아무렇게나 이어도 지키는 불변식(#87, SDD §5.3). */
class LayoutNodeProperties {

    /** 한 단계: 나누기(대상 위치 · 방향) 또는 닫기(대상 위치). 위치는 그때의 panes() 크기로 나눈 나머지다. */
    record Step(boolean split, int index, Orientation orientation) {}

    @Provide
    Arbitrary<List<Step>> steps() {
        Arbitrary<Step> step = Combinators.combine(
                        Arbitraries.of(true, true, false), // 나누기를 더 자주 — 트리가 자라게
                        Arbitraries.integers().between(0, 1000),
                        Arbitraries.of(Orientation.values()))
                .as(Step::new);
        return step.list().ofMaxSize(40);
    }

    private static void checkInvariants(LayoutNode tree) {
        List<PaneId> panes = tree.panes();
        assertThat(new HashSet<>(panes)).hasSameSizeAs(panes);
        assertRatios(tree);
    }

    private static void assertRatios(LayoutNode node) {
        if (node instanceof LayoutNode.Split split) {
            assertThat(split.ratio().value()).isBetween(0.1, 0.9);
            assertRatios(split.first());
            assertRatios(split.second());
        }
    }

    @Property
    @Label("임의의 split · close 열 뒤에도 창 ID 는 유일하고 비율은 0.1~0.9 이며 창 수 = 1 + split 수 − close 성공 수")
    void invariantsHold(@ForAll("steps") List<Step> steps) {
        LayoutNode tree = new LayoutNode.Leaf(new PaneId("p0"));
        int splits = 0;
        int closes = 0;
        int next = 1;
        for (Step step : steps) {
            List<PaneId> panes = tree.panes();
            PaneId target = panes.get(step.index() % panes.size());
            if (step.split()) {
                tree = tree.split(target, step.orientation(), new PaneId("p" + next++))
                        .value()
                        .orElseThrow();
                splits++;
            } else {
                var closed = tree.close(target);
                if (closed.isOk()) {
                    tree = closed.value().orElseThrow();
                    closes++;
                } else {
                    assertThat(closed.error()).contains(new LayoutError.LastPane(target));
                }
            }
            checkInvariants(tree);
        }
        assertThat(tree.panes()).hasSize(1 + splits - closes);
    }

    @Property
    @Label("split 한 뒤 새로 생긴 창을 닫으면 원래 트리로 돌아간다")
    void closeUndoesSplit(@ForAll("steps") List<Step> steps, @ForAll Orientation orientation, @ForAll int pick) {
        // given: 임의의 트리
        LayoutNode tree = new LayoutNode.Leaf(new PaneId("p0"));
        int next = 1;
        for (Step step : steps) {
            if (step.split()) {
                List<PaneId> panes = tree.panes();
                PaneId fresh = new PaneId("p" + next++);
                tree = tree.split(panes.get(step.index() % panes.size()), step.orientation(), fresh)
                        .value()
                        .orElseThrow();
            }
        }
        List<PaneId> panes = tree.panes();
        PaneId target = panes.get(Math.floorMod(pick, panes.size()));

        // when
        LayoutNode split =
                tree.split(target, orientation, new PaneId("fresh")).value().orElseThrow();
        LayoutNode undone = split.close(new PaneId("fresh")).value().orElseThrow();

        // then
        assertThat(undone).isEqualTo(tree);
    }
}
