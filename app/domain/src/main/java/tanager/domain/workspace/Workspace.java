package tanager.domain.workspace;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tanager.domain.history.WorkspacePath;
import tanager.domain.layout.Direction;
import tanager.domain.layout.DropZone;
import tanager.domain.layout.LayoutError;
import tanager.domain.layout.LayoutGeometry;
import tanager.domain.layout.LayoutNode;
import tanager.domain.layout.Orientation;
import tanager.domain.layout.Ratio;
import tanager.domain.layout.Rect;
import tanager.domain.layout.SplitPath;
import tanager.domain.session.ProfileId;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;

/**
 * 창 배치와 창별 시작 명세(#88, SDD §5.4). 지금은 탭 하나다(탭은 M5). 불변식: 명세가 있는 창 = 트리의 창. 새 창 번호는 늘기만 한다 — 닫은
 * 창의 번호를 다시 쓰지 않아, 늦게 온 이전 창의 이벤트가 새 창에 붙지 않는다. 명세는 창 ID 를 따라간다 — 교환 · 옮기기는 자리만 바꾼다.
 *
 * <p>줌 규칙(#147, FR-LAY-05): 레이아웃을 바꾸는 연산(분할 · 닫기 · 크기 · 교환 · 옮기기)과 다른 창으로의 포커스는 줌을 푼다 — 숨은 창이
 * 바뀌거나 숨은 창으로 포커스가 가면 줌 화면이 실제 배치와 어긋나서다. 같은 창에 다시 포커스하면 줌은 그대로다. 실패한 연산은 아무것도 바꾸지
 * 않는다.
 *
 * @param nextPane 다음에 만들 창 번호(p{nextPane})
 */
public record Workspace(Tab tab, Map<PaneId, PaneSpec> specs, int nextPane) {
    private static final PaneId FIRST = new PaneId("p1");
    /** 키보드 크기 조절 한 번에 옮기는 몫(FR-LAY-02). */
    private static final double RESIZE_STEP = 0.05;

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

    /**
     * 포커스 창 옆({@code orientation} — ROW 오른쪽, COLUMN 아래)에 새 창을 열고 그 창에 포커스한다. {@code dir} 이 비면 그 셸의 시작 경로(→
     * 홈)에서 뜬다.
     */
    public Opened openBeside(Orientation orientation, ProfileId profile, Optional<WorkspacePath> dir) {
        Objects.requireNonNull(profile, "profile");
        return split(orientation, Optional.of(profile), dir);
    }

    /**
     * 포커스 창을 나눠(FR-LAY-01, {@code orientation} — ROW 오른쪽, COLUMN 아래) 새 창을 열고 그 창에 포커스한다. 줌은 풀린다.
     *
     * @param profile 새 창의 프로파일. 비면 기억한 기본 셸
     * @param dir 새 창의 시작 경로. 비면 그 프로파일의 시작 경로 → 홈
     */
    public Opened split(Orientation orientation, Optional<ProfileId> profile, Optional<WorkspacePath> dir) {
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
        next.put(created, new PaneSpec(created, profile, dir));
        return new Opened(new Workspace(new Tab(root, created), next, nextPane + 1), created);
    }

    /**
     * {@code pane} 을 닫는다. 포커스 창이면 남는 형제 쪽의 첫 창에 포커스한다. 줌은 풀린다. 마지막 창이면 {@link LayoutError.LastPane}.
     */
    public Result<Workspace, LayoutError> close(PaneId pane) {
        Objects.requireNonNull(pane, "pane");
        Optional<LayoutNode> sibling = siblingOf(tab.root(), pane);
        return tab.root().close(pane).map(root -> {
            PaneId focused =
                    tab.focused().equals(pane) ? sibling.orElseThrow().panes().getFirst() : tab.focused();
            Map<PaneId, PaneSpec> next = new HashMap<>(specs);
            next.remove(pane);
            return new Workspace(new Tab(root, focused), next, nextPane);
        });
    }

    /** {@code pane} 에 포커스한다. 다른 창이면 줌이 풀리고, 이미 포커스한 창이면 그대로다. */
    public Result<Workspace, LayoutError> focus(PaneId pane) {
        Objects.requireNonNull(pane, "pane");
        if (!specs.containsKey(pane)) {
            return Result.err(new LayoutError.UnknownPane(pane));
        }
        if (pane.equals(tab.focused())) {
            return Result.ok(this);
        }
        return Result.ok(new Workspace(new Tab(tab.root(), pane), specs, nextPane));
    }

    /**
     * 방향 포커스(FR-LAY-06). {@code bounds} · {@code gap} 으로 그린 배치에서 포커스 창의 {@code direction} 쪽 이웃({@link
     * LayoutGeometry#neighbor})에 포커스한다. 없으면 {@link LayoutError.NoNeighbor}.
     */
    public Result<Workspace, LayoutError> focus(Direction direction, Rect bounds, int gap) {
        return neighbor(direction, bounds, gap).flatMap(this::focus);
    }

    /** 포커스 창의 경계를 {@code direction} 쪽으로 5% 옮긴다(FR-LAY-02, {@link LayoutNode#resize}). */
    public Result<Workspace, LayoutError> resize(Direction direction) {
        return tab.root().resize(tab.focused(), direction, RESIZE_STEP).map(this::withRoot);
    }

    /** {@code path} 가 가리키는 Split 의 비율을 {@code ratio} 로 바꾼다(FR-LAY-11 경계선 드래그). */
    public Result<Workspace, LayoutError> resizeTo(SplitPath path, Ratio ratio) {
        return tab.root().resizeTo(path, ratio).map(this::withRoot);
    }

    /** 포커스 창을 줌한다(FR-LAY-05). 줌 상태면 푼다. 창이 하나면 줌할 게 없어 그대로다. */
    public Workspace toggleZoom() {
        if (tab.zoomed().isPresent()) {
            return new Workspace(new Tab(tab.root(), tab.focused()), specs, nextPane);
        }
        if (tab.root().panes().size() == 1) {
            return this;
        }
        return new Workspace(new Tab(tab.root(), tab.focused(), Optional.of(tab.focused())), specs, nextPane);
    }

    /**
     * 포커스 창을 {@code direction} 쪽 이웃과 바꾼다(FR-LAY-03). 포커스는 옮겨 간 그 창에 남는다. 이웃이 없으면 {@link
     * LayoutError.NoNeighbor}.
     */
    public Result<Workspace, LayoutError> swap(Direction direction, Rect bounds, int gap) {
        return neighbor(direction, bounds, gap)
                .flatMap(other -> tab.root().swap(tab.focused(), other))
                .map(this::withRoot);
    }

    /**
     * 창을 끌어다 놓는다(FR-LAY-12). 가운데면 대상 창과 바꾸고, 변이면 대상 창의 그쪽에 붙인다. 옮긴 창에 포커스한다. 두 창이 같으면 {@link
     * LayoutError.SamePane}, 없는 창이면 {@link LayoutError.UnknownPane}.
     */
    public Result<Workspace, LayoutError> move(PaneId pane, DropZone zone) {
        Objects.requireNonNull(pane, "pane");
        Objects.requireNonNull(zone, "zone");
        Result<LayoutNode, LayoutError> moved = switch (zone) {
            case DropZone.Center center -> tab.root().swap(pane, center.target());
            case DropZone.Side side -> tab.root().move(pane, side.target(), side.edge());
        };
        return moved.map(root -> new Workspace(new Tab(root, pane), specs, nextPane));
    }

    private Result<PaneId, LayoutError> neighbor(Direction direction, Rect bounds, int gap) {
        Objects.requireNonNull(direction, "direction");
        PaneId from = tab.focused();
        return LayoutGeometry.neighbor(tab.root(), from, direction, bounds, gap)
                .<Result<PaneId, LayoutError>>map(Result::ok)
                .orElseGet(() -> Result.err(new LayoutError.NoNeighbor(from, direction)));
    }

    // 창 집합은 그대로인 새 트리. 포커스는 유지하고 줌은 푼다
    private Workspace withRoot(LayoutNode root) {
        return new Workspace(new Tab(root, tab.focused()), specs, nextPane);
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
