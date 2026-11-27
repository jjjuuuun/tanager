package tanager.domain.workspace;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tanager.domain.history.WorkspacePath;
import tanager.domain.layout.LayoutError;
import tanager.domain.layout.LayoutNode;
import tanager.domain.layout.Orientation;
import tanager.domain.session.ProfileId;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;

/**
 * 창 배치와 창별 시작 명세(#88, SDD §5.4). 지금은 탭 하나다(탭은 M5). 불변식: 명세가 있는 창 = 트리의 창. 새 창 번호는 늘기만 한다 — 닫은
 * 창의 번호를 다시 쓰지 않아, 늦게 온 이전 창의 이벤트가 새 창에 붙지 않는다.
 *
 * @param nextPane 다음에 만들 창 번호(p{nextPane})
 */
public record Workspace(Tab tab, Map<PaneId, PaneSpec> specs, int nextPane) {
    private static final PaneId FIRST = new PaneId("p1");

    public Workspace {
        Objects.requireNonNull(tab, "tab");
        specs = Map.copyOf(specs);
        if (!specs.keySet().equals(Set.copyOf(tab.root().panes()))) {
            throw new IllegalArgumentException("명세가 있는 창과 트리의 창이 다르다: " + specs.keySet());
        }
        specs.forEach((pane, spec) -> {
            if (!spec.pane().equals(pane)) {
                throw new IllegalArgumentException("명세의 창이 키와 다르다: " + pane);
            }
        });
        if (nextPane < 1) {
            throw new IllegalArgumentException("nextPane 은 1 이상이다: " + nextPane);
        }
    }

    /** 앱을 열 때: 기억한 셸을 띄울 창 p1 하나. */
    public static Workspace initial() {
        return new Workspace(new Tab(new LayoutNode.Leaf(FIRST), FIRST), Map.of(FIRST, PaneSpec.remembered(FIRST)), 2);
    }

    /** 연 결과: 새 작업 공간과 새 창. */
    public record Opened(Workspace workspace, PaneId pane) {
        public Opened {
            Objects.requireNonNull(workspace, "workspace");
            Objects.requireNonNull(pane, "pane");
        }
    }

    /** {@code pane} 의 시작 명세. */
    public Optional<PaneSpec> spec(PaneId pane) {
        return Optional.ofNullable(specs.get(pane));
    }

    /** 포커스 창 옆({@code orientation} — ROW 오른쪽, COLUMN 아래)에 새 창을 열고 그 창에 포커스한다. */
    public Opened openBeside(Orientation orientation, ProfileId profile, WorkspacePath dir) {
        Objects.requireNonNull(orientation, "orientation");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(dir, "dir");
        PaneId created = new PaneId("p" + nextPane);
        // 포커스 창은 트리에 있고(Tab 불변식) 새 번호는 아직 없는 번호다 — 실패하면 버그다
        LayoutNode root = tab.root()
                .split(tab.focused(), orientation, created)
                .value()
                .orElseThrow(() -> new IllegalStateException("새 창 " + created + " 을 열 수 없다"));
        Map<PaneId, PaneSpec> next = new HashMap<>(specs);
        next.put(created, new PaneSpec(created, Optional.of(profile), Optional.of(dir)));
        return new Opened(new Workspace(new Tab(root, created), next, nextPane + 1), created);
    }

    /**
     * {@code pane} 을 닫는다. 포커스 창이면 남는 형제 쪽의 첫 창에 포커스한다. 줌한 창이면 줌이 풀린다(다른 창을 닫으면 줌은 그대로). 마지막 창이면
     * {@link LayoutError.LastPane}.
     */
    public Result<Workspace, LayoutError> close(PaneId pane) {
        Objects.requireNonNull(pane, "pane");
        Optional<LayoutNode> sibling = siblingOf(tab.root(), pane);
        return tab.root().close(pane).map(root -> {
            PaneId focused =
                    tab.focused().equals(pane) ? sibling.orElseThrow().panes().getFirst() : tab.focused();
            Optional<PaneId> zoomed = tab.zoomed().filter(z -> !z.equals(pane));
            Map<PaneId, PaneSpec> next = new HashMap<>(specs);
            next.remove(pane);
            return new Workspace(new Tab(root, focused, zoomed), next, nextPane);
        });
    }

    /** {@code pane} 에 포커스한다. */
    public Result<Workspace, LayoutError> focus(PaneId pane) {
        Objects.requireNonNull(pane, "pane");
        if (!specs.containsKey(pane)) {
            return Result.err(new LayoutError.UnknownPane(pane));
        }
        return Result.ok(new Workspace(new Tab(tab.root(), pane), specs, nextPane));
    }

    // pane 을 바로 아래 자식으로 가진 Split 의 다른 자식
    private static Optional<LayoutNode> siblingOf(LayoutNode node, PaneId pane) {
        return switch (node) {
            case LayoutNode.Leaf _ -> Optional.empty();
            case LayoutNode.Split split -> {
                if (split.first() instanceof LayoutNode.Leaf(PaneId p) && p.equals(pane)) {
                    yield Optional.of(split.second());
                }
                if (split.second() instanceof LayoutNode.Leaf(PaneId p) && p.equals(pane)) {
                    yield Optional.of(split.first());
                }
                yield siblingOf(split.first(), pane).or(() -> siblingOf(split.second(), pane));
            }
        };
    }
}
