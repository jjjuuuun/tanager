package tanager.domain.layout;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;

/** split · close · resize · resizeTo · swap · move 를 아무렇게나 이어도 지키는 불변식(#146, SDD §5.3). */
class LayoutOperationsProperties {

    enum Kind {
        SPLIT,
        CLOSE,
        RESIZE,
        RESIZE_TO,
        SWAP,
        MOVE
    }

    /** 한 단계. 위치 {@code a} · {@code b} 는 그때의 panes()(resizeTo 는 Split 경로 목록) 크기로 나눈 나머지다. */
    record Op(Kind kind, int a, int b, Orientation orientation, Direction direction, Edge edge, double amount) {}

    @Provide
    Arbitrary<List<Op>> ops() {
        Arbitrary<Op> op = Combinators.combine(
                        // 나누기를 더 자주 — 트리가 자라야 다른 연산이 할 일이 생긴다
                        Arbitraries.of(
                                Kind.SPLIT, Kind.SPLIT, Kind.CLOSE, Kind.RESIZE, Kind.RESIZE_TO, Kind.SWAP, Kind.MOVE),
                        Arbitraries.integers().between(0, 1000),
                        Arbitraries.integers().between(0, 1000),
                        Arbitraries.of(Orientation.values()),
                        Arbitraries.of(Direction.values()),
                        Arbitraries.of(Edge.values()),
                        Arbitraries.doubles().between(0, 1))
                .as(Op::new);
        return op.list().ofMaxSize(40);
    }

    @Property
    @Label("임의의 연산 열 뒤에도 창 ID 는 유일하고 비율은 0.1~0.9 이며 move · swap · resize 는 창 집합을 바꾸지 않는다")
    void invariantsHold(@ForAll("ops") List<Op> ops) {
        LayoutNode tree = new LayoutNode.Leaf(new PaneId("p0"));
        int next = 1;
        for (Op op : ops) {
            List<PaneId> panes = tree.panes();
            PaneId a = panes.get(op.a() % panes.size());
            PaneId b = panes.get(op.b() % panes.size());
            Result<LayoutNode, LayoutError> result = switch (op.kind()) {
                case SPLIT -> tree.split(a, op.orientation(), new PaneId("p" + next++));
                case CLOSE -> tree.close(a);
                case RESIZE -> tree.resize(a, op.direction(), op.amount());
                case RESIZE_TO -> resizeTo(tree, op);
                case SWAP -> tree.swap(a, b);
                case MOVE -> tree.move(a, b, op.edge());
            };
            if (result.isOk()) {
                LayoutNode after = result.value().orElseThrow();
                if (op.kind() != Kind.SPLIT && op.kind() != Kind.CLOSE) {
                    assertThat(Set.copyOf(after.panes())).isEqualTo(Set.copyOf(panes));
                }
                tree = after;
            } else {
                assertThat(result.error().orElseThrow()).isIn(expectedErrors(op, a, b));
            }
            checkInvariants(tree);
        }
    }

    // 실패해도 되는 경우: 이 오류들 말고는 버그다
    private static List<LayoutError> expectedErrors(Op op, PaneId a, PaneId b) {
        return switch (op.kind()) {
            case SPLIT -> List.of();
            case CLOSE -> List.of(new LayoutError.LastPane(a));
            case RESIZE -> List.of(new LayoutError.NoSplitInDirection(a, op.direction()));
            case RESIZE_TO -> List.of(new LayoutError.UnknownSplit(SplitPath.ROOT));
            case SWAP, MOVE -> a.equals(b) ? List.of(new LayoutError.SamePane(a)) : List.of();
        };
    }

    // Split 이 하나도 없으면 루트 경로로 불러 UnknownSplit 을 확인한다
    private static Result<LayoutNode, LayoutError> resizeTo(LayoutNode tree, Op op) {
        List<SplitPath> paths = splitPaths(tree, SplitPath.ROOT, new ArrayList<>());
        SplitPath path = paths.isEmpty() ? SplitPath.ROOT : paths.get(op.a() % paths.size());
        return tree.resizeTo(path, new Ratio(Ratio.MIN + (Ratio.MAX - Ratio.MIN) * op.amount()));
    }

    private static List<SplitPath> splitPaths(LayoutNode node, SplitPath here, List<SplitPath> out) {
        if (node instanceof LayoutNode.Split split) {
            out.add(here);
            splitPaths(split.first(), here.then(SplitPath.Branch.FIRST), out);
            splitPaths(split.second(), here.then(SplitPath.Branch.SECOND), out);
        }
        return out;
    }

    private static void checkInvariants(LayoutNode tree) {
        List<PaneId> panes = tree.panes();
        assertThat(new HashSet<>(panes)).hasSameSizeAs(panes);
        assertRatios(tree);
    }

    private static void assertRatios(LayoutNode node) {
        if (node instanceof LayoutNode.Split split) {
            assertThat(split.ratio().value()).isBetween(Ratio.MIN, Ratio.MAX);
            assertRatios(split.first());
            assertRatios(split.second());
        }
    }
}
