package tanager.domain.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import tanager.domain.layout.LayoutError;
import tanager.domain.layout.Orientation;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;
import tanager.domain.shared.TabId;

/** 탭 · 창 연산을 아무렇게나 이어도 지키는 작업 공간의 불변식(#176). */
class WorkspaceTabsProperties {

    enum Kind {
        NEW_TAB,
        CLOSE_TAB,
        RENAME,
        MOVE_TAB,
        SELECT,
        SELECT_RELATIVE,
        SELECT_INDEX,
        MOVE_PANE_TO_TAB,
        MOVE_PANE_TO_NEW_TAB,
        SPLIT,
        CLOSE,
        FOCUS,
        TOGGLE_ZOOM
    }

    /** 한 단계. {@code a} · {@code b} 는 그때의 창 · 탭 목록 크기로 나눈 나머지(위치 인수는 그대로 쓴다). */
    record Op(Kind kind, int a, int b) {}

    @Provide
    Arbitrary<List<Op>> ops() {
        Arbitrary<Op> op = Combinators.combine(
                        Arbitraries.of(Kind.values()),
                        Arbitraries.integers().between(-3, 1000),
                        Arbitraries.integers().between(-3, 1000))
                .as(Op::new);
        return op.list().ofMaxSize(40);
    }

    @Property
    @Label("임의의 탭 · 창 연산 열 뒤에도 탭 ≥ 1 · 활성 범위 · ID 유일 · 창 집합 분할 = 명세 · 번호 단조 증가가 성립한다")
    void invariantsHold(@ForAll("ops") List<Op> ops) {
        Workspace workspace = Workspace.initial();
        Set<PaneId> everPanes = new HashSet<>(workspace.specs().keySet());
        Set<TabId> everTabs = new HashSet<>(List.of(workspace.tab().id()));
        for (Op op : ops) {
            Workspace before = workspace;
            workspace = apply(workspace, op);
            assertThat(workspace.nextPane()).isGreaterThanOrEqualTo(before.nextPane());
            assertThat(workspace.nextTab()).isGreaterThanOrEqualTo(before.nextTab());
            // 닫은 번호를 다시 쓰지 않는다: 새로 나타난 ID 는 처음 보는 ID 다
            for (PaneId pane : workspace.specs().keySet()) {
                if (!before.specs().containsKey(pane)) {
                    assertThat(everPanes.add(pane)).isTrue();
                }
            }
            for (Tab tab : workspace.tabs()) {
                if (before.tabs().stream().noneMatch(t -> t.id().equals(tab.id()))) {
                    assertThat(everTabs.add(tab.id())).isTrue();
                }
            }
            checkInvariants(workspace);
        }
    }

    @Property
    @Label("newTab 뒤 closeTab(새 탭) 은 탭 목록과 명세가 원래와 같다")
    void newTabThenCloseTabRestores(@ForAll("ops") List<Op> ops) {
        // given
        Workspace workspace = build(ops);

        // when
        Workspace.Opened opened = workspace.newTab(Optional.empty(), Optional.empty());
        Workspace closed = opened.workspace()
                .closeTab(opened.workspace().tab().id())
                .value()
                .orElseThrow();

        // then
        assertThat(closed.tabs()).isEqualTo(workspace.tabs());
        assertThat(closed.specs()).isEqualTo(workspace.specs());
    }

    @Property
    @Label("movePaneToTab · movePaneToNewTab 전후 명세가 같고, 옮긴 창은 대상 탭의 포커스 창이다")
    void movingKeepsSpecs(@ForAll("ops") List<Op> ops, @ForAll int paneIndex, @ForAll int tabIndex) {
        // given
        Workspace workspace = build(ops);
        PaneId pane = pick(List.copyOf(workspace.specs().keySet()), paneIndex);
        TabId target = pick(workspace.tabs(), tabIndex).id();

        // when
        Result<Workspace, TabError> toTab = workspace.movePaneToTab(pane, target);
        Result<Workspace, TabError> toNewTab = workspace.movePaneToNewTab(pane);

        // then
        for (Result<Workspace, TabError> result : List.of(toTab, toNewTab)) {
            result.value().ifPresent(moved -> {
                assertThat(moved.specs()).isEqualTo(workspace.specs());
                assertThat(moved.tab().focused()).isEqualTo(pane);
            });
        }
        boolean sameTab = workspace.tabOf(pane).orElseThrow().equals(target);
        assertThat(toTab.isOk()).isEqualTo(!sameTab);
        boolean sole = workspace.tabs().stream().anyMatch(t -> t.root().panes().equals(List.of(pane)));
        assertThat(toNewTab.isOk()).isEqualTo(!sole);
    }

    private static Workspace build(List<Op> ops) {
        Workspace workspace = Workspace.initial();
        for (Op op : ops) {
            workspace = apply(workspace, op);
        }
        return workspace;
    }

    // 실패한 연산은 바뀌지 않은 작업 공간을 그대로 쓴다. 실패는 예상한 오류여야 한다
    private static Workspace apply(Workspace workspace, Op op) {
        List<PaneId> panes = List.copyOf(workspace.specs().keySet());
        PaneId pane = pick(panes, op.a());
        TabId tab = pick(workspace.tabs(), op.b()).id();
        return switch (op.kind()) {
            case NEW_TAB -> workspace.newTab(Optional.empty(), Optional.empty()).workspace();
            case CLOSE_TAB -> orKeep(workspace, workspace.closeTab(tab), List.of(new TabError.LastTab(tab)));
            case RENAME ->
                orKeep(workspace, workspace.rename(tab, TabName.of("탭" + op.a()).value()), List.of());
            case MOVE_TAB -> orKeep(workspace, workspace.moveTab(tab, op.a()), List.of());
            case SELECT -> orKeep(workspace, workspace.select(tab), List.of());
            case SELECT_RELATIVE -> workspace.selectRelative(op.a());
            case SELECT_INDEX -> {
                int index = op.a() % (workspace.tabs().size() + 1);
                yield orKeep(workspace, workspace.selectIndex(index), List.of(new TabError.NoSuchIndex(index)));
            }
            case MOVE_PANE_TO_TAB ->
                orKeep(workspace, workspace.movePaneToTab(pane, tab), List.of(new TabError.SameTab(tab)));
            case MOVE_PANE_TO_NEW_TAB ->
                orKeep(workspace, workspace.movePaneToNewTab(pane), List.of(new TabError.SolePane(pane)));
            case SPLIT ->
                workspace
                        .split(Orientation.values()[Math.floorMod(op.a(), 2)], Optional.empty(), Optional.empty())
                        .workspace();
            case CLOSE -> orKeep(workspace, workspace.close(pane), List.of(new LayoutError.LastPane(pane)));
            case FOCUS -> orKeep(workspace, workspace.focus(pane), List.of());
            case TOGGLE_ZOOM -> workspace.toggleZoom();
        };
    }

    private static <E> Workspace orKeep(Workspace workspace, Result<Workspace, E> result, List<?> expected) {
        result.error().ifPresent(error -> assertThat(error).isIn(expected));
        return result.value().orElse(workspace);
    }

    private static <T> T pick(List<T> items, int index) {
        return items.get(Math.floorMod(index, items.size()));
    }

    private static void checkInvariants(Workspace workspace) {
        List<Tab> tabs = workspace.tabs();
        assertThat(tabs).isNotEmpty();
        assertThat(workspace.active()).isBetween(0, tabs.size() - 1);
        assertThat(tabs.stream().map(Tab::id).distinct().count()).isEqualTo(tabs.size());
        Set<PaneId> union = new HashSet<>();
        int total = 0;
        for (Tab tab : tabs) {
            List<PaneId> panes = tab.root().panes();
            union.addAll(panes);
            total += panes.size();
            assertThat(panes).contains(tab.focused());
            tab.zoomed().ifPresent(zoomed -> assertThat(panes).contains(zoomed));
            assertThat(number(tab.id().value())).isLessThan(workspace.nextTab());
            assertThat(workspace.tabOf(tab.focused())).contains(tab.id());
        }
        assertThat(union).hasSize(total).isEqualTo(workspace.specs().keySet());
        for (PaneId pane : union) {
            assertThat(number(pane.value())).isLessThan(workspace.nextPane());
        }
    }

    private static int number(String id) {
        return Integer.parseInt(id.substring(1));
    }
}
