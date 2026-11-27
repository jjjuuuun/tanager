package tanager.domain.workspace;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
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
import tanager.domain.shared.TabId;

/**
 * 탭 목록 · 활성 탭 · 창별 시작 명세(#88 · #176, SDD §5.4). 창 연산(분할 · 크기 · 방향 포커스 · 줌 · 교환 · 옮기기)은 활성 탭에 적용한다.
 *
 * <p>불변식: 탭이 하나 이상 · {@code 0 ≤ active < tabs.size()} · 탭 ID 유일 · 탭들의 창 집합은 서로 겹치지 않고 그 합집합 = 명세가 있는 창
 * · {@code nextPane} 은 모든 {@code p<n>} 보다, {@code nextTab} 은 모든 {@code t<n>} 보다 크다. 새 창 · 탭 번호는 늘기만 한다 — 닫은
 * 번호를 다시 쓰지 않아, 늦게 온 이전 창의 이벤트가 새 창에 붙지 않는다. 명세는 창 ID 를 따라간다 — 교환 · 옮기기는 자리만 바꾼다.
 *
 * <p>줌 규칙(#147, FR-LAY-05)은 탭마다 따로다: 레이아웃을 바꾸는 연산(분할 · 닫기 · 크기 · 교환 · 옮기기)과 다른 창으로의 포커스는 그 탭의
 * 줌을 푼다 — 숨은 창이 바뀌거나 숨은 창으로 포커스가 가면 줌 화면이 실제 배치와 어긋나서다. 같은 창에 다시 포커스하거나 탭을 바꾸면 줌은
 * 그대로다. 실패한 연산은 아무것도 바꾸지 않는다.
 *
 * @param active 활성 탭의 위치(0 부터)
 * @param nextPane 다음에 만들 창 번호(p{nextPane})
 * @param nextTab 다음에 만들 탭 번호(t{nextTab})
 */
public record Workspace(List<Tab> tabs, int active, Map<PaneId, PaneSpec> specs, int nextPane, int nextTab) {
    private static final PaneId FIRST_PANE = new PaneId("p1");
    private static final TabId FIRST_TAB = new TabId("t1");
    private static final String PANE_PREFIX = "p";
    private static final String TAB_PREFIX = "t";
    /** 키보드 크기 조절 한 번에 옮기는 몫(FR-LAY-02). */
    private static final double RESIZE_STEP = 0.05;

    public Workspace {
        tabs = List.copyOf(tabs);
        specs = Map.copyOf(specs);
        if (tabs.isEmpty()) {
            throw new IllegalArgumentException("탭이 하나 이상이어야 한다");
        }
        if (active < 0 || active >= tabs.size()) {
            throw new IllegalArgumentException("활성 탭 위치가 범위 밖이다: " + active);
        }
        Set<TabId> ids = new HashSet<>();
        Set<PaneId> panes = new HashSet<>();
        for (Tab tab : tabs) {
            if (!ids.add(tab.id())) {
                throw new IllegalArgumentException("탭 ID 가 겹친다: " + tab.id());
            }
            for (PaneId pane : tab.root().panes()) {
                if (!panes.add(pane)) {
                    throw new IllegalArgumentException("창이 두 탭에 있다: " + pane);
                }
            }
        }
        if (!specs.keySet().equals(panes)) {
            throw new IllegalArgumentException("명세가 있는 창과 탭들의 창이 다르다: " + specs.keySet());
        }
        specs.forEach((pane, spec) -> {
            if (!spec.pane().equals(pane)) {
                throw new IllegalArgumentException("명세의 창이 키와 다르다: " + pane);
            }
        });
        for (PaneId pane : panes) {
            requireBelow(pane.value(), PANE_PREFIX, nextPane, "nextPane");
        }
        for (TabId id : ids) {
            requireBelow(id.value(), TAB_PREFIX, nextTab, "nextTab");
        }
        if (nextPane < 1 || nextTab < 1) {
            throw new IllegalArgumentException("다음 번호는 1 이상이다: " + nextPane + ", " + nextTab);
        }
    }

    /** 앱을 열 때: 기억한 셸을 띄울 창 p1 하나를 가진 탭 t1. */
    public static Workspace initial() {
        return single(
                new Tab(FIRST_TAB, new LayoutNode.Leaf(FIRST_PANE), FIRST_PANE),
                Map.of(FIRST_PANE, PaneSpec.remembered(FIRST_PANE)),
                2);
    }

    /** 탭 {@code tab} 하나짜리 작업 공간. 다음 탭 번호는 그 탭 번호 다음이다. */
    public static Workspace single(Tab tab, Map<PaneId, PaneSpec> specs, int nextPane) {
        Objects.requireNonNull(tab, "tab");
        long used = number(tab.id().value(), TAB_PREFIX).orElse(0);
        // 너무 큰 번호는 그대로 넘겨 불변식 검사가 거부하게 한다
        int nextTab = (int) Math.min(used + 1, Integer.MAX_VALUE);
        return new Workspace(List.of(tab), 0, specs, nextPane, nextTab);
    }

    /** 연 결과: 새 작업 공간과 새 창. */
    public record Opened(Workspace workspace, PaneId pane) {
        public Opened {
            Objects.requireNonNull(workspace, "workspace");
            Objects.requireNonNull(pane, "pane");
        }
    }

    /** 활성 탭. */
    public Tab tab() {
        return tabs.get(active);
    }

    /** {@code pane} 의 시작 명세. */
    public Optional<PaneSpec> spec(PaneId pane) {
        return Optional.ofNullable(specs.get(pane));
    }

    /** {@code pane} 이 든 탭. 없는 창이면 비어 있다. */
    public Optional<TabId> tabOf(PaneId pane) {
        Objects.requireNonNull(pane, "pane");
        OptionalInt index = indexOfPane(pane);
        return index.isPresent() ? Optional.of(tabs.get(index.getAsInt()).id()) : Optional.empty();
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
     * 활성 탭의 포커스 창을 나눠(FR-LAY-01, {@code orientation} — ROW 오른쪽, COLUMN 아래) 새 창을 열고 그 창에 포커스한다. 줌은 풀린다.
     *
     * @param profile 새 창의 프로파일. 비면 기억한 기본 셸
     * @param dir 새 창의 시작 경로. 비면 그 프로파일의 시작 경로 → 홈
     */
    public Opened split(Orientation orientation, Optional<ProfileId> profile, Optional<WorkspacePath> dir) {
        Objects.requireNonNull(orientation, "orientation");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(dir, "dir");
        PaneId created = new PaneId(PANE_PREFIX + nextPane);
        Tab tab = tab();
        // 포커스 창은 트리에 있고(Tab 불변식) 새 번호는 아직 없는 번호다 — 실패하면 버그다
        LayoutNode root = tab.root()
                .split(tab.focused(), orientation, created)
                .value()
                .orElseThrow(() -> new IllegalStateException("새 창 " + created + " 을 열 수 없다"));
        Map<PaneId, PaneSpec> next = new HashMap<>(specs);
        next.put(created, new PaneSpec(created, profile, dir));
        return new Opened(
                new Workspace(replaced(active, tab.withLayout(root, created)), active, next, nextPane + 1, nextTab),
                created);
    }

    /**
     * {@code pane} 을 닫는다(FR-LAY-04). 그 탭에 창이 둘 이상이면 트리에서 빼고, 포커스 창이었으면 남는 형제 쪽의 첫 창에 포커스한다 — 그 탭의
     * 줌은 풀린다. 탭의 유일한 창이면 탭을 닫는다({@link #closeTab}). 모든 탭을 통틀어 마지막 창이면 {@link LayoutError.LastPane}.
     */
    public Result<Workspace, LayoutError> close(PaneId pane) {
        Objects.requireNonNull(pane, "pane");
        OptionalInt found = indexOfPane(pane);
        if (found.isEmpty()) {
            return Result.err(new LayoutError.UnknownPane(pane));
        }
        int index = found.getAsInt();
        Tab owner = tabs.get(index);
        if (owner.sole()) {
            return tabs.size() == 1 ? Result.err(new LayoutError.LastPane(pane)) : Result.ok(removeTab(index));
        }
        Map<PaneId, PaneSpec> next = new HashMap<>(specs);
        next.remove(pane);
        return owner.without(pane).map(tab -> new Workspace(replaced(index, tab), active, next, nextPane, nextTab));
    }

    /**
     * {@code pane} 에 포커스한다. 다른 탭의 창이면 그 탭을 활성화한다(사이드바 세션 포커스). 그 탭에서 다른 창이면 그 탭의 줌이 풀리고, 이미
     * 포커스한 창이면 그대로다.
     */
    public Result<Workspace, LayoutError> focus(PaneId pane) {
        Objects.requireNonNull(pane, "pane");
        OptionalInt found = indexOfPane(pane);
        if (found.isEmpty()) {
            return Result.err(new LayoutError.UnknownPane(pane));
        }
        int index = found.getAsInt();
        return Result.ok(
                new Workspace(replaced(index, tabs.get(index).withFocus(pane)), index, specs, nextPane, nextTab));
    }

    /**
     * 방향 포커스(FR-LAY-06). {@code bounds} · {@code gap} 으로 그린 활성 탭 배치에서 포커스 창의 {@code direction} 쪽 이웃({@link
     * LayoutGeometry#neighbor})에 포커스한다. 없으면 {@link LayoutError.NoNeighbor}.
     */
    public Result<Workspace, LayoutError> focus(Direction direction, Rect bounds, int gap) {
        return neighbor(direction, bounds, gap).flatMap(this::focus);
    }

    /** 포커스 창의 경계를 {@code direction} 쪽으로 5% 옮긴다(FR-LAY-02, {@link LayoutNode#resize}). */
    public Result<Workspace, LayoutError> resize(Direction direction) {
        return tab().root().resize(tab().focused(), direction, RESIZE_STEP).map(this::withRoot);
    }

    /** 활성 탭에서 {@code path} 가 가리키는 Split 의 비율을 {@code ratio} 로 바꾼다(FR-LAY-11 경계선 드래그). */
    public Result<Workspace, LayoutError> resizeTo(SplitPath path, Ratio ratio) {
        return tab().root().resizeTo(path, ratio).map(this::withRoot);
    }

    /** 포커스 창을 줌한다(FR-LAY-05). 줌 상태면 푼다. 창이 하나면 줌할 게 없어 그대로다. */
    public Workspace toggleZoom() {
        Tab tab = tab();
        if (tab.zoomed().isPresent()) {
            return withActiveTab(tab.withZoom(Optional.empty()));
        }
        if (tab.sole()) {
            return this;
        }
        return withActiveTab(tab.withZoom(Optional.of(tab.focused())));
    }

    /**
     * 포커스 창을 {@code direction} 쪽 이웃과 바꾼다(FR-LAY-03). 포커스는 옮겨 간 그 창에 남는다. 이웃이 없으면 {@link
     * LayoutError.NoNeighbor}.
     */
    public Result<Workspace, LayoutError> swap(Direction direction, Rect bounds, int gap) {
        return neighbor(direction, bounds, gap)
                .flatMap(other -> tab().root().swap(tab().focused(), other))
                .map(this::withRoot);
    }

    /**
     * 활성 탭 안에서 창을 끌어다 놓는다(FR-LAY-12). 가운데면 대상 창과 바꾸고, 변이면 대상 창의 그쪽에 붙인다. 옮긴 창에 포커스한다. 두 창이
     * 같으면 {@link LayoutError.SamePane}, 활성 탭에 없는 창이면 {@link LayoutError.UnknownPane}. 다른 탭으로는 {@link #movePaneToTab}.
     */
    public Result<Workspace, LayoutError> move(PaneId pane, DropZone zone) {
        Objects.requireNonNull(pane, "pane");
        Objects.requireNonNull(zone, "zone");
        LayoutNode root = tab().root();
        Result<LayoutNode, LayoutError> moved = switch (zone) {
            case DropZone.Center center -> root.swap(pane, center.target());
            case DropZone.Side side -> root.move(pane, side.target(), side.edge());
        };
        return moved.map(next -> withActiveTab(tab().withLayout(next, pane)));
    }

    /**
     * 새 창 하나짜리 탭을 맨 뒤에 붙이고 활성화한다(FR-LAY-07 · FR-HIS-05 "새 탭").
     *
     * @param profile 새 창의 프로파일. 비면 기억한 기본 셸
     * @param dir 새 창의 시작 경로. 비면 그 프로파일의 시작 경로 → 홈
     */
    public Opened newTab(Optional<ProfileId> profile, Optional<WorkspacePath> dir) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(dir, "dir");
        PaneId created = new PaneId(PANE_PREFIX + nextPane);
        List<Tab> next = new ArrayList<>(tabs);
        next.add(new Tab(new TabId(TAB_PREFIX + nextTab), new LayoutNode.Leaf(created), created));
        Map<PaneId, PaneSpec> nextSpecs = new HashMap<>(specs);
        nextSpecs.put(created, new PaneSpec(created, profile, dir));
        return new Opened(new Workspace(next, next.size() - 1, nextSpecs, nextPane + 1, nextTab + 1), created);
    }

    /**
     * 탭을 닫고 그 탭 창들의 명세도 지운다(FR-LAY-07). 활성 탭이었으면 오른쪽 이웃, 없으면 왼쪽 이웃이 활성이 된다. 탭이 하나뿐이면 {@link
     * TabError.LastTab}.
     */
    public Result<Workspace, TabError> closeTab(TabId tab) {
        return find(tab)
                .flatMap(index ->
                        tabs.size() == 1 ? Result.err(new TabError.LastTab(tab)) : Result.ok(removeTab(index)));
    }

    /** 탭 이름을 바꾼다(FR-LAY-07). {@code name} 이 비면 자동 이름(포커스 창 제목)으로 돌아간다. */
    public Result<Workspace, TabError> rename(TabId tab, Optional<TabName> name) {
        Objects.requireNonNull(name, "name");
        return find(tab)
                .map(index -> new Workspace(
                        replaced(index, tabs.get(index).withName(name)), active, specs, nextPane, nextTab));
    }

    /** 탭을 {@code toIndex}(0 부터) 자리로 옮긴다(FR-LAY-07). 범위 밖은 양 끝으로 고정한다. 활성 탭은 그대로다. */
    public Result<Workspace, TabError> moveTab(TabId tab, int toIndex) {
        return find(tab).map(index -> {
            TabId activeId = tab().id();
            List<Tab> next = new ArrayList<>(tabs);
            Tab moved = next.remove((int) index);
            next.add(Math.clamp(toIndex, 0, next.size()), moved);
            return new Workspace(next, indexIn(next, activeId), specs, nextPane, nextTab);
        });
    }

    /** {@code tab} 을 활성화한다. 각 탭의 포커스 · 줌은 그대로다. */
    public Result<Workspace, TabError> select(TabId tab) {
        return find(tab).map(this::withActive);
    }

    /** 활성 탭에서 {@code delta} 만큼 옆 탭을 활성화한다(Ctrl+Tab · Ctrl+Shift+Tab). 양 끝에서 반대쪽으로 돈다. */
    public Workspace selectRelative(int delta) {
        return withActive(Math.floorMod((long) active + delta, tabs.size()));
    }

    /** {@code index}(0 부터) 자리의 탭을 활성화한다(Ctrl+1…9 → 0…8). 없는 번호면 {@link TabError.NoSuchIndex}. */
    public Result<Workspace, TabError> selectIndex(int index) {
        return index >= 0 && index < tabs.size()
                ? Result.ok(withActive(index))
                : Result.err(new TabError.NoSuchIndex(index));
    }

    /**
     * 창을 다른 탭으로 옮긴다(FR-LAY-12 탭 위에 놓기). 대상 탭 포커스 창의 오른쪽에 붙이고 대상 탭을 활성화해 그 창에 포커스한다. 원래 탭의
     * 유일한 창이었으면 빈 탭은 닫힌다. 두 탭의 줌은 풀린다. 명세는 그대로다. 이미 그 탭이면 {@link TabError.SameTab}.
     */
    public Result<Workspace, TabError> movePaneToTab(PaneId pane, TabId target) {
        Objects.requireNonNull(pane, "pane");
        OptionalInt found = indexOfPane(pane);
        if (found.isEmpty()) {
            return Result.err(new TabError.UnknownPane(pane));
        }
        int from = found.getAsInt();
        return find(target).flatMap(to -> {
            if (from == to) {
                return Result.err(new TabError.SameTab(target));
            }
            Tab destination = tabs.get(to);
            // 대상 탭의 포커스 창은 그 트리에 있고 옮길 창은 다른 탭의 창이다 — 실패하면 버그다
            LayoutNode root = destination
                    .root()
                    .split(destination.focused(), Orientation.ROW, pane)
                    .value()
                    .orElseThrow(() -> new IllegalStateException(pane + " 을 " + target + " 로 옮길 수 없다"));
            List<Tab> next = new ArrayList<>(tabs);
            next.set(to, destination.withLayout(root, pane));
            Tab source = tabs.get(from);
            if (source.sole()) {
                next.remove(from);
            } else {
                next.set(from, remaining(source, pane));
            }
            return Result.ok(new Workspace(next, indexIn(next, target), specs, nextPane, nextTab));
        });
    }

    /** 창을 맨 뒤의 새 탭으로 옮기고 활성화한다(FR-LAY-10). 명세는 그대로다. 탭의 유일한 창이면 {@link TabError.SolePane}. */
    public Result<Workspace, TabError> movePaneToNewTab(PaneId pane) {
        Objects.requireNonNull(pane, "pane");
        OptionalInt found = indexOfPane(pane);
        if (found.isEmpty()) {
            return Result.err(new TabError.UnknownPane(pane));
        }
        int from = found.getAsInt();
        Tab source = tabs.get(from);
        if (source.sole()) {
            return Result.err(new TabError.SolePane(pane));
        }
        List<Tab> next = new ArrayList<>(tabs);
        next.set(from, remaining(source, pane));
        next.add(new Tab(new TabId(TAB_PREFIX + nextTab), new LayoutNode.Leaf(pane), pane));
        return Result.ok(new Workspace(next, next.size() - 1, specs, nextPane, nextTab + 1));
    }

    private Result<PaneId, LayoutError> neighbor(Direction direction, Rect bounds, int gap) {
        Objects.requireNonNull(direction, "direction");
        PaneId from = tab().focused();
        return LayoutGeometry.neighbor(tab().root(), from, direction, bounds, gap)
                .<Result<PaneId, LayoutError>>map(Result::ok)
                .orElseGet(() -> Result.err(new LayoutError.NoNeighbor(from, direction)));
    }

    // 활성 탭의 창 집합은 그대로인 새 트리. 포커스는 유지하고 줌은 푼다
    private Workspace withRoot(LayoutNode root) {
        return withActiveTab(tab().withLayout(root, tab().focused()));
    }

    private Workspace withActiveTab(Tab tab) {
        return new Workspace(replaced(active, tab), active, specs, nextPane, nextTab);
    }

    private Workspace withActive(int index) {
        return new Workspace(tabs, index, specs, nextPane, nextTab);
    }

    private List<Tab> replaced(int index, Tab tab) {
        List<Tab> next = new ArrayList<>(tabs);
        next.set(index, tab);
        return next;
    }

    // index 자리 탭과 그 창들의 명세를 지운다. 활성 탭이었으면 오른쪽 이웃(없으면 왼쪽)이 활성
    private Workspace removeTab(int index) {
        List<Tab> next = new ArrayList<>(tabs);
        Tab removed = next.remove(index);
        Map<PaneId, PaneSpec> nextSpecs = new HashMap<>(specs);
        nextSpecs.keySet().removeAll(removed.root().panes());
        int nextActive = index < active ? active - 1 : index == active ? Math.min(index, next.size() - 1) : active;
        return new Workspace(next, nextActive, nextSpecs, nextPane, nextTab);
    }

    private Result<Integer, TabError> find(TabId tab) {
        Objects.requireNonNull(tab, "tab");
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).id().equals(tab)) {
                return Result.ok(i);
            }
        }
        return Result.err(new TabError.UnknownTab(tab));
    }

    private OptionalInt indexOfPane(PaneId pane) {
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).contains(pane)) {
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }

    // 창이 둘 이상인 탭에서 pane 을 뺀 탭
    private static Tab remaining(Tab source, PaneId pane) {
        return source.without(pane)
                .value()
                .orElseThrow(() -> new IllegalStateException(pane + " 을 " + source.id() + " 에서 뺄 수 없다"));
    }

    private static int indexIn(List<Tab> tabs, TabId id) {
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).id().equals(id)) {
                return i;
            }
        }
        throw new IllegalStateException("탭이 없다: " + id);
    }

    private static void requireBelow(String id, String prefix, int next, String field) {
        OptionalLong used = number(id, prefix);
        if (used.isPresent() && used.getAsLong() >= next) {
            throw new IllegalArgumentException(field + " 은 쓰인 " + id + " 보다 커야 한다: " + next);
        }
    }

    // "p12" → 12. 번호 꼴(접두어 + 숫자)이 아니면 비어 있다. 아주 긴 숫자는 어떤 int 보다도 크게 본다
    private static OptionalLong number(String id, String prefix) {
        if (!id.startsWith(prefix) || id.length() == prefix.length()) {
            return OptionalLong.empty();
        }
        String digits = id.substring(prefix.length());
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                return OptionalLong.empty();
            }
        }
        return OptionalLong.of(digits.length() > 18 ? Long.MAX_VALUE : Long.parseLong(digits));
    }
}
