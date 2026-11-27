package tanager.adapter.persistence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import tanager.application.port.out.WorkspaceLoadNotice;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.layout.LayoutNode;
import tanager.domain.layout.Orientation;
import tanager.domain.layout.Ratio;
import tanager.domain.session.ProfileId;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;
import tanager.domain.shared.TabId;
import tanager.domain.workspace.Bounds;
import tanager.domain.workspace.PaneSpec;
import tanager.domain.workspace.PanelState;
import tanager.domain.workspace.Tab;
import tanager.domain.workspace.TabName;
import tanager.domain.workspace.WindowState;
import tanager.domain.workspace.Workspace;
import tanager.domain.workspace.WorkspaceSnapshot;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * state.json v2 의 workspace · window 부분(#178, ADR-0013 개정)을 읽고 쓴다. 값이 틀려도 문서 전체를 손상으로 보지 않는다 — 창 하나가 틀리면 그
 * 창만 트리에서 빼고(형제로 축약), 탭의 창이 모두 틀리면 그 탭을, 남는 탭이 없으면 workspace 를, window 값이 틀리면 창 상태만 기본값으로 버린다.
 */
final class WorkspaceJson {
    static final String PART_WORKSPACE = "workspace";
    static final String PART_WINDOW = "window";

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final String ACTIVE_TAB = "activeTab";
    private static final String TABS = "tabs";
    private static final String PANES = "panes";
    private static final String ID = "id";
    private static final String NAME = "name";
    private static final String FOCUSED = "focused";
    private static final String ZOOMED = "zoomed";
    private static final String LAYOUT = "layout";
    private static final String PANE = "pane";
    private static final String SPLIT = "split";
    private static final String RATIO = "ratio";
    private static final String FIRST = "first";
    private static final String SECOND = "second";
    private static final String PROFILE = "profile";
    private static final String LAST_CWD = "lastCwd";
    private static final String ENV = "env";
    private static final String PATH = "path";
    private static final List<String> BOUNDS = List.of("x", "y", "width", "height");
    private static final String MAXIMIZED = "maximized";
    private static final String SIDEBAR = "sidebar";
    private static final String TOOLKIT = "toolkit";
    private static final String VISIBLE = "visible";
    private static final String WIDTH = "width";

    private WorkspaceJson() {}

    /** 읽은 스냅샷과 알림. */
    record Read(Optional<WorkspaceSnapshot> snapshot, List<WorkspaceLoadNotice> notices) {
        Read {
            Objects.requireNonNull(snapshot, "snapshot");
            notices = List.copyOf(notices);
        }
    }

    // ---- 읽기 ----

    /** workspace 가 없으면 스냅샷도 없다(window 만으로는 복원하지 않는다). */
    static Read read(Optional<JsonNode> workspace, Optional<JsonNode> window) {
        if (workspace.isEmpty()) {
            return new Read(Optional.empty(), List.of());
        }
        List<WorkspaceLoadNotice> notices = new ArrayList<>();
        Optional<Workspace> restored = new WorkspaceReader(notices).read(workspace.get());
        if (restored.isEmpty()) {
            return new Read(Optional.empty(), notices);
        }
        WindowState state = WindowState.DEFAULT;
        if (window.isPresent()) {
            Result<WindowState, String> parsed = window(window.get());
            if (parsed instanceof Result.Err<WindowState, String>(String reason)) {
                notices.add(new WorkspaceLoadNotice.PartDiscarded(PART_WINDOW, reason));
            } else {
                state = parsed.value().orElseThrow();
            }
        }
        return new Read(Optional.of(new WorkspaceSnapshot(restored.get(), state)), notices);
    }

    // 한 번 읽는 동안의 상태: 쓰인 창 · 탭 ID 와 뺀 창 수
    private static final class WorkspaceReader {
        private final List<WorkspaceLoadNotice> notices;
        private final Map<PaneId, PaneSpec> specs = new HashMap<>();
        private final Set<PaneId> used = new HashSet<>();
        private final Set<TabId> ids = new HashSet<>();
        private int dropped;

        WorkspaceReader(List<WorkspaceLoadNotice> notices) {
            this.notices = notices;
        }

        Optional<Workspace> read(JsonNode node) {
            if (!(node instanceof ObjectNode workspace)) {
                return discard("객체가 아니다");
            }
            if (!(workspace.get(TABS) instanceof ArrayNode tabs)) {
                return discard("tabs 가 배열이 아니다");
            }
            JsonNode panes = workspace.get(PANES);
            if (panes != null && !(panes instanceof ObjectNode)) {
                return discard("panes 가 객체가 아니다");
            }
            if (panes instanceof ObjectNode object) {
                for (Map.Entry<String, JsonNode> entry : object.properties()) {
                    if (!entry.getKey().isBlank()) {
                        PaneId pane = new PaneId(entry.getKey());
                        spec(pane, entry.getValue()).ifPresent(spec -> specs.put(pane, spec));
                    }
                }
            }
            List<Tab> restored = new ArrayList<>();
            int active = 0;
            JsonNode activeNode = workspace.get(ACTIVE_TAB);
            int wanted = activeNode != null && activeNode.isInt() ? activeNode.intValue() : 0;
            for (int i = 0; i < tabs.size(); i++) {
                Optional<Tab> tab = tab(tabs.get(i));
                if (tab.isPresent()) {
                    if (i == wanted) {
                        active = restored.size();
                    }
                    restored.add(tab.get());
                }
            }
            if (dropped > 0) {
                notices.add(new WorkspaceLoadNotice.PanesDropped(dropped));
            }
            if (restored.isEmpty()) {
                return discard("남는 탭이 없다");
            }
            Map<PaneId, PaneSpec> kept = new HashMap<>();
            for (PaneId pane : used) {
                kept.put(pane, specs.get(pane));
            }
            try {
                return Optional.of(Workspace.restore(restored, active, kept));
            } catch (IllegalArgumentException e) {
                return discard(String.valueOf(e.getMessage()));
            }
        }

        private Optional<Workspace> discard(String reason) {
            notices.add(new WorkspaceLoadNotice.PartDiscarded(PART_WORKSPACE, reason));
            return Optional.empty();
        }

        // 틀린 탭(객체가 아님 · ID 가 없거나 겹침 · 남는 창이 없음)은 버리고 그 창들을 뺀 수에 센다
        private Optional<Tab> tab(JsonNode node) {
            if (!(node instanceof ObjectNode tab)) {
                return Optional.empty();
            }
            Optional<TabId> id = JsonValues.nonBlank(tab.get(ID)).map(TabId::new);
            if (id.isEmpty() || ids.contains(id.get())) {
                dropped += leaves(tab.get(LAYOUT));
                return Optional.empty();
            }
            Optional<LayoutNode> root = layout(tab.get(LAYOUT));
            if (root.isEmpty()) {
                return Optional.empty();
            }
            ids.add(id.get());
            List<PaneId> panes = root.get().panes();
            PaneId focused = paneIn(tab.get(FOCUSED), panes).orElse(panes.getFirst());
            // 틀린 이름은 이름만 버린다(자동 이름) — 이름 하나 때문에 탭을 잃지 않게
            Optional<TabName> name =
                    JsonValues.string(tab.get(NAME)).flatMap(s -> TabName.of(s).value());
            return Optional.of(new Tab(id.get(), name, root.get(), focused, paneIn(tab.get(ZOOMED), panes)));
        }

        // 명세가 없거나 이미 쓰인 창 · 틀린 분할 노드(그 아래 창 모두)는 빼고 형제로 축약한다
        private Optional<LayoutNode> layout(JsonNode node) {
            if (!(node instanceof ObjectNode object)) {
                return Optional.empty();
            }
            if (object.has(PANE)) {
                return leaf(object.get(PANE));
            }
            Optional<Orientation> orientation = orientation(object.get(SPLIT));
            Optional<Ratio> ratio = ratio(object.get(RATIO));
            if (orientation.isEmpty() || ratio.isEmpty() || !object.has(FIRST) || !object.has(SECOND)) {
                dropped += leaves(object);
                return Optional.empty();
            }
            Optional<LayoutNode> first = layout(object.get(FIRST));
            Optional<LayoutNode> second = layout(object.get(SECOND));
            if (first.isPresent() && second.isPresent()) {
                return Optional.of(new LayoutNode.Split(orientation.get(), ratio.get(), first.get(), second.get()));
            }
            return first.or(() -> second);
        }

        private Optional<LayoutNode> leaf(JsonNode id) {
            Optional<PaneId> pane = JsonValues.nonBlank(id).map(PaneId::new);
            if (pane.isPresent() && specs.containsKey(pane.get()) && used.add(pane.get())) {
                return Optional.of(new LayoutNode.Leaf(pane.get()));
            }
            dropped++;
            return Optional.empty();
        }
    }

    private static Optional<PaneSpec> spec(PaneId pane, JsonNode node) {
        if (!(node instanceof ObjectNode object)) {
            return Optional.empty();
        }
        Optional<Optional<ProfileId>> profile = nullable(object.get(PROFILE), WorkspaceJson::profile);
        Optional<Optional<WorkspacePath>> dir = nullable(object.get(LAST_CWD), WorkspaceJson::cwd);
        if (profile.isEmpty() || dir.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PaneSpec(pane, profile.get(), dir.get()));
    }

    private static Optional<ProfileId> profile(JsonNode node) {
        return JsonValues.nonBlank(node).map(ProfileId::new);
    }

    private static Optional<WorkspacePath> cwd(JsonNode node) {
        Optional<ShellEnvironment> env = Environments.read(node.get(ENV));
        Optional<String> path = JsonValues.string(node.get(PATH));
        if (env.isEmpty() || path.isEmpty()) {
            return Optional.empty();
        }
        return WorkspacePath.of(env.get(), path.get()).value();
    }

    // 바깥 empty = 틀린 값, 안쪽 empty = 값 없음(null · 키 없음)
    private static <T> Optional<Optional<T>> nullable(JsonNode node, Function<JsonNode, Optional<T>> parse) {
        if (node == null || node.isNull()) {
            return Optional.of(Optional.empty());
        }
        return parse.apply(node).map(Optional::of);
    }

    private static Optional<PaneId> paneIn(JsonNode node, List<PaneId> panes) {
        return JsonValues.string(node).map(PaneId::new).filter(panes::contains);
    }

    private static Optional<Orientation> orientation(JsonNode node) {
        Optional<String> split = JsonValues.string(node);
        for (Orientation orientation : Orientation.values()) {
            if (split.equals(Optional.of(orientation.name()))) {
                return Optional.of(orientation);
            }
        }
        return Optional.empty();
    }

    private static Optional<Ratio> ratio(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return Optional.empty();
        }
        double value = node.doubleValue();
        return value >= Ratio.MIN && value <= Ratio.MAX ? Optional.of(new Ratio(value)) : Optional.empty();
    }

    // 틀린 노드 아래에서 창으로 보이는 잎의 수 — 뺀 창 수 알림용
    private static int leaves(JsonNode node) {
        if (!(node instanceof ObjectNode object)) {
            return 0;
        }
        if (object.has(PANE)) {
            return 1;
        }
        return leaves(object.get(FIRST)) + leaves(object.get(SECOND));
    }

    // 빠진 필드는 기본값, 틀린 값은 오류(창 상태 전체를 버린다)
    private static Result<WindowState, String> window(JsonNode node) {
        if (!(node instanceof ObjectNode window)) {
            return Result.err("객체가 아니다");
        }
        Result<Optional<Bounds>, String> bounds = bounds(window);
        if (bounds instanceof Result.Err<Optional<Bounds>, String>(String reason)) {
            return Result.err(reason);
        }
        JsonNode maximized = window.get(MAXIMIZED);
        if (maximized != null && !maximized.isBoolean()) {
            return Result.err("maximized 가 불리언이 아니다");
        }
        Optional<PanelState> sidebar = panel(window.get(SIDEBAR), WindowState.DEFAULT.sidebar());
        Optional<PanelState> toolkit = panel(window.get(TOOLKIT), WindowState.DEFAULT.toolkit());
        if (sidebar.isEmpty()) {
            return Result.err("sidebar 가 틀렸다");
        }
        if (toolkit.isEmpty()) {
            return Result.err("toolkit 이 틀렸다");
        }
        return Result.ok(new WindowState(
                bounds.value().orElseThrow(),
                maximized != null && maximized.booleanValue(),
                sidebar.get(),
                toolkit.get()));
    }

    // 좌표 넷이 모두 없으면 OS 가 정한 자리, 일부만 있거나 정수가 아니거나 크기 < 1 이면 오류
    private static Result<Optional<Bounds>, String> bounds(ObjectNode window) {
        long present = BOUNDS.stream().filter(window::has).count();
        if (present == 0) {
            return Result.ok(Optional.empty());
        }
        if (present < BOUNDS.size()) {
            return Result.err("x · y · width · height 중 일부만 있다");
        }
        List<JsonNode> values = BOUNDS.stream().map(window::get).toList();
        if (!values.stream().allMatch(JsonNode::isInt)
                || values.get(2).intValue() < 1
                || values.get(3).intValue() < 1) {
            return Result.err("x · y · width · height 가 틀렸다");
        }
        return Result.ok(Optional.of(new Bounds(
                values.get(0).intValue(),
                values.get(1).intValue(),
                values.get(2).intValue(),
                values.get(3).intValue())));
    }

    private static Optional<PanelState> panel(JsonNode node, PanelState fallback) {
        if (node == null) {
            return Optional.of(fallback);
        }
        if (!(node instanceof ObjectNode panel)) {
            return Optional.empty();
        }
        JsonNode visible = panel.get(VISIBLE);
        JsonNode width = panel.get(WIDTH);
        if ((visible != null && !visible.isBoolean()) || (width != null && (!width.isInt() || width.intValue() < 0))) {
            return Optional.empty();
        }
        return Optional.of(new PanelState(
                visible == null ? fallback.visible() : visible.booleanValue(),
                width == null ? fallback.width() : width.intValue()));
    }

    // ---- 쓰기 ----

    /** 탭 순서 · 창은 탭 안 순서대로. 다음 창 · 탭 번호는 쓰지 않는다(읽을 때 계산). */
    static ObjectNode workspace(Workspace workspace) {
        ObjectNode node = NODES.objectNode();
        node.put(ACTIVE_TAB, workspace.active());
        ArrayNode tabs = node.putArray(TABS);
        ObjectNode panes = NODES.objectNode();
        for (Tab tab : workspace.tabs()) {
            ObjectNode t = tabs.addObject();
            t.put(ID, tab.id().value());
            tab.name().ifPresentOrElse(n -> t.put(NAME, n.value()), () -> t.putNull(NAME));
            t.put(FOCUSED, tab.focused().value());
            tab.zoomed().ifPresentOrElse(z -> t.put(ZOOMED, z.value()), () -> t.putNull(ZOOMED));
            t.set(LAYOUT, layout(tab.root()));
            for (PaneId pane : tab.root().panes()) {
                panes.set(pane.value(), spec(workspace.spec(pane).orElseThrow()));
            }
        }
        node.set(PANES, panes);
        return node;
    }

    private static ObjectNode layout(LayoutNode node) {
        ObjectNode out = NODES.objectNode();
        switch (node) {
            case LayoutNode.Leaf leaf -> out.put(PANE, leaf.pane().value());
            case LayoutNode.Split split -> {
                out.put(SPLIT, split.orientation().name());
                out.put(RATIO, split.ratio().value());
                out.set(FIRST, layout(split.first()));
                out.set(SECOND, layout(split.second()));
            }
        }
        return out;
    }

    private static ObjectNode spec(PaneSpec spec) {
        ObjectNode out = NODES.objectNode();
        spec.profile().ifPresentOrElse(p -> out.put(PROFILE, p.value()), () -> out.putNull(PROFILE));
        spec.dir()
                .ifPresentOrElse(
                        dir -> {
                            ObjectNode cwd = out.putObject(LAST_CWD);
                            Environments.write(cwd.putObject(ENV), dir.env());
                            cwd.put(PATH, dir.path());
                        },
                        () -> out.putNull(LAST_CWD));
        return out;
    }

    /** {@code base}(load 때 읽은 window) 위에 아는 필드만 덮어써 모르는 필드(예: {@code toolkit.tab})를 보존한다. */
    static ObjectNode window(WindowState state, Optional<ObjectNode> base) {
        ObjectNode out = base.map(ObjectNode::deepCopy).orElseGet(NODES::objectNode);
        state.bounds()
                .ifPresentOrElse(
                        b -> out.put("x", b.x())
                                .put("y", b.y())
                                .put("width", b.width())
                                .put("height", b.height()),
                        () -> out.remove(BOUNDS));
        out.put(MAXIMIZED, state.maximized());
        panel(out, SIDEBAR, state.sidebar());
        panel(out, TOOLKIT, state.toolkit());
        return out;
    }

    private static void panel(ObjectNode window, String key, PanelState state) {
        ObjectNode panel = window.get(key) instanceof ObjectNode existing ? existing : window.putObject(key);
        panel.put(VISIBLE, state.visible()).put(WIDTH, state.width());
    }
}
