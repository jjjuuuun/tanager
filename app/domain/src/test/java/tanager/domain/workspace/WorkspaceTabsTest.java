package tanager.domain.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.layout.LayoutError;
import tanager.domain.layout.LayoutNode;
import tanager.domain.layout.Orientation;
import tanager.domain.layout.Ratio;
import tanager.domain.session.ProfileId;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.TabId;

/** 여러 탭: 만들기 · 닫기 · 이름 · 순서 · 고르기 · 창 옮기기(#176, FR-LAY-04 · 07 · 10 · 12). */
class WorkspaceTabsTest {
    private static final PaneId P1 = new PaneId("p1");
    private static final PaneId P2 = new PaneId("p2");
    private static final PaneId P3 = new PaneId("p3");
    private static final PaneId P9 = new PaneId("p9");
    private static final TabId T1 = new TabId("t1");
    private static final TabId T2 = new TabId("t2");
    private static final TabId T3 = new TabId("t3");
    private static final TabId T9 = new TabId("t9");
    private static final ProfileId PWSH = new ProfileId("powershell");
    private static final WorkspacePath A =
            WorkspacePath.of(new ShellEnvironment.Windows(), "C:\\a").value().orElseThrow();

    private static Workspace newTab(Workspace workspace) {
        return workspace.newTab(Optional.empty(), Optional.empty()).workspace();
    }

    private static Workspace split(Workspace workspace) {
        return workspace
                .split(Orientation.ROW, Optional.empty(), Optional.empty())
                .workspace();
    }

    /** [t1(p1), t2(p2), t3(p3)], 활성 t3. */
    private static Workspace threeTabs() {
        return newTab(newTab(Workspace.initial()));
    }

    private static <T> T ok(tanager.domain.shared.Result<T, ?> result) {
        return result.value().orElseThrow(() -> new AssertionError("실패: " + result.error()));
    }

    private static List<TabId> ids(Workspace workspace) {
        return workspace.tabs().stream().map(Tab::id).toList();
    }

    private static Tab tab(Workspace workspace, TabId id) {
        return workspace.tabs().stream()
                .filter(t -> t.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static LayoutNode row(PaneId first, PaneId second) {
        return new LayoutNode.Split(
                Orientation.ROW, Ratio.HALF, new LayoutNode.Leaf(first), new LayoutNode.Leaf(second));
    }

    @Test
    @DisplayName("시작 상태는 이름 없는 탭 t1 하나(창 p1)이고, 다음 번호는 p2 · t2 다")
    void initial() {
        // when
        Workspace workspace = Workspace.initial();

        // then
        assertThat(ids(workspace)).containsExactly(T1);
        assertThat(workspace.active()).isZero();
        assertThat(workspace.tab().name()).isEmpty();
        assertThat(workspace.nextPane()).isEqualTo(2);
        assertThat(workspace.nextTab()).isEqualTo(2);
    }

    @Test
    @DisplayName("newTab 은 맨 뒤에 새 창 하나짜리 탭을 붙이고 활성화한다 · 번호는 둘 다 늘어난다")
    void newTabAppendsAndActivates() {
        // when
        Workspace.Opened opened = Workspace.initial().newTab(Optional.empty(), Optional.empty());

        // then
        Workspace workspace = opened.workspace();
        assertThat(opened.pane()).isEqualTo(P2);
        assertThat(ids(workspace)).containsExactly(T1, T2);
        assertThat(workspace.tab().id()).isEqualTo(T2);
        assertThat(workspace.tab().root()).isEqualTo(new LayoutNode.Leaf(P2));
        assertThat(workspace.tab().focused()).isEqualTo(P2);
        assertThat(workspace.spec(P2)).contains(PaneSpec.remembered(P2));
        assertThat(workspace.nextPane()).isEqualTo(3);
        assertThat(workspace.nextTab()).isEqualTo(3);
    }

    @Test
    @DisplayName("newTab 에 프로파일 · 경로를 주면 새 창 명세가 그대로 된다")
    void newTabWithProfileAndDir() {
        // when
        Workspace.Opened opened = Workspace.initial().newTab(Optional.of(PWSH), Optional.of(A));

        // then
        assertThat(opened.workspace().spec(P2)).contains(new PaneSpec(P2, Optional.of(PWSH), Optional.of(A)));
    }

    @Test
    @DisplayName("창 연산은 활성 탭에만 적용되고 다른 탭은 그대로다")
    void paneOperationsApplyToActiveTab() {
        // given: [t1(p1), t2(p2)], 활성 t2
        Workspace two = newTab(Workspace.initial());

        // when
        Workspace splitInT2 = split(two);

        // then
        assertThat(tab(splitInT2, T1)).isEqualTo(tab(two, T1));
        assertThat(splitInT2.tab().root()).isEqualTo(row(P2, P3));
    }

    @Test
    @DisplayName("탭의 유일한 창을 닫으면 그 탭이 닫히고 명세도 지운다 — 활성 탭이 맨 끝이면 왼쪽이 활성")
    void closeSolePaneClosesTab() {
        // given: [t1(p1), t2(p2)], 활성 t2
        Workspace two = newTab(Workspace.initial());

        // when
        Workspace closed = ok(two.close(P2));

        // then
        assertThat(ids(closed)).containsExactly(T1);
        assertThat(closed.tab().id()).isEqualTo(T1);
        assertThat(closed.spec(P2)).isEmpty();
        assertThat(closed.specs().keySet()).containsExactly(P1);
    }

    @Test
    @DisplayName("활성 탭을 닫으면 오른쪽 이웃이 활성 · 활성이 아닌 탭을 닫으면 활성 탭은 그대로다")
    void closeTabActivation() {
        // given: [t1, t2, t3], 활성 t2
        Workspace middle = ok(threeTabs().select(T2));

        // then
        assertThat(ok(middle.close(P2)).tab().id()).isEqualTo(T3);
        assertThat(ok(middle.close(P1)).tab().id()).isEqualTo(T2);
        assertThat(ok(middle.close(P3)).tab().id()).isEqualTo(T2);
    }

    @Test
    @DisplayName("다른 탭의 창을 닫으면 그 탭에서만 닫고 활성 탭은 그대로다")
    void closePaneInOtherTab() {
        // given: [t1(p1 | p2), t2(p3)], 활성 t2
        Workspace workspace = newTab(split(Workspace.initial()));

        // when
        Workspace closed = ok(workspace.close(P1));

        // then
        assertThat(closed.tab().id()).isEqualTo(T2);
        assertThat(tab(closed, T1).root()).isEqualTo(new LayoutNode.Leaf(P2));
        assertThat(tab(closed, T1).focused()).isEqualTo(P2);
    }

    @Test
    @DisplayName("모든 탭을 통틀어 마지막 창이면 LastPane 이고, 없는 창이면 UnknownPane 이다")
    void closeLastPane() {
        assertThat(Workspace.initial().close(P1).error()).contains(new LayoutError.LastPane(P1));
        assertThat(newTab(Workspace.initial()).close(P9).error()).contains(new LayoutError.UnknownPane(P9));
    }

    @Test
    @DisplayName("closeTab 은 탭과 그 창들의 명세를 지우고, 탭이 하나뿐이면 LastTab · 없는 탭이면 UnknownTab")
    void closeTab() {
        // given: [t1(p1), t2(p2 | p3)], 활성 t2
        Workspace workspace = split(newTab(Workspace.initial()));

        // when
        Workspace closed = ok(workspace.closeTab(T2));

        // then
        assertThat(ids(closed)).containsExactly(T1);
        assertThat(closed.specs().keySet()).containsExactly(P1);
        assertThat(Workspace.initial().closeTab(T1).error()).contains(new TabError.LastTab(T1));
        assertThat(workspace.closeTab(T9).error()).contains(new TabError.UnknownTab(T9));
    }

    @Test
    @DisplayName("moveTab 은 순서만 바꾸고 활성 탭은 그대로 · 범위 밖 위치는 양 끝으로 고정한다")
    void moveTab() {
        // given: [t1, t2, t3], 활성 t2
        Workspace workspace = ok(threeTabs().select(T2));

        // when
        Workspace moved = ok(workspace.moveTab(T3, 0));

        // then
        assertThat(ids(moved)).containsExactly(T3, T1, T2);
        assertThat(moved.tab().id()).isEqualTo(T2);
        assertThat(ids(ok(workspace.moveTab(T1, 99)))).containsExactly(T2, T3, T1);
        assertThat(ids(ok(workspace.moveTab(T3, -5)))).containsExactly(T3, T1, T2);
        assertThat(workspace.moveTab(T9, 0).error()).contains(new TabError.UnknownTab(T9));
    }

    @Test
    @DisplayName("rename 은 이름을 붙이고, empty 면 자동 이름(포커스 창 제목)으로 돌아간다 · 없는 탭이면 UnknownTab")
    void rename() {
        // given
        TabName build = TabName.of("빌드").value().orElseThrow();

        // when
        Workspace named = ok(Workspace.initial().rename(T1, Optional.of(build)));
        Workspace auto = ok(named.rename(T1, Optional.empty()));

        // then
        assertThat(named.tab().name()).contains(build);
        assertThat(auto.tab().name()).isEmpty();
        assertThat(Workspace.initial().rename(T9, Optional.of(build)).error()).contains(new TabError.UnknownTab(T9));
    }

    @Test
    @DisplayName("다른 탭의 창에 포커스하면 그 탭이 활성이 되고 그 창에 포커스한다")
    void focusPaneInOtherTab() {
        // given: [t1(p1), t2(p2 | p3)], t2 의 포커스 p2, 활성 t1
        Workspace workspace =
                ok(ok(split(newTab(Workspace.initial())).focus(P2)).select(T1));

        // when
        Workspace focused = ok(workspace.focus(P3));

        // then
        assertThat(focused.tab().id()).isEqualTo(T2);
        assertThat(focused.tab().focused()).isEqualTo(P3);
    }

    @Test
    @DisplayName("다른 탭에서 이미 포커스한 창으로 가면 그 탭의 줌이 그대로다(줌 규칙은 탭마다)")
    void focusOtherTabKeepsItsZoom() {
        // given: [t1(p1 | p2) p1 줌, t2(p3)], 활성 t2
        Workspace workspace = newTab(ok(split(Workspace.initial()).focus(P1)).toggleZoom());

        // when
        Workspace back = ok(workspace.focus(P1));

        // then
        assertThat(back.tab().id()).isEqualTo(T1);
        assertThat(back.tab().zoomed()).contains(P1);
    }

    @Test
    @DisplayName("탭을 바꿔도 각 탭의 포커스와 줌은 그대로다")
    void selectKeepsZoom() {
        // given: [t1(p1 | p2) p1 줌, t2(p3)]
        Workspace workspace = newTab(ok(split(Workspace.initial()).focus(P1)).toggleZoom());

        // when
        Workspace back = ok(ok(workspace.select(T2)).select(T1));

        // then
        assertThat(back.tab().zoomed()).contains(P1);
        assertThat(back.tab().focused()).isEqualTo(P1);
        assertThat(workspace.select(T9).error()).contains(new TabError.UnknownTab(T9));
    }

    @Test
    @DisplayName("selectRelative 는 순환하고, selectIndex 는 0 부터 세며 없는 번호면 NoSuchIndex 다")
    void selectRelativeAndIndex() {
        // given: [t1, t2, t3], 활성 t3
        Workspace workspace = threeTabs();

        // then
        assertThat(workspace.selectRelative(+1).tab().id()).isEqualTo(T1);
        assertThat(workspace.selectRelative(-1).tab().id()).isEqualTo(T2);
        assertThat(workspace.selectRelative(-1).selectRelative(+2).tab().id()).isEqualTo(T1);
        assertThat(ok(workspace.selectIndex(0)).tab().id()).isEqualTo(T1);
        assertThat(workspace.selectIndex(5).error()).contains(new TabError.NoSuchIndex(5));
        assertThat(workspace.selectIndex(-1).error()).contains(new TabError.NoSuchIndex(-1));
    }

    @Test
    @DisplayName("movePaneToTab 은 창을 대상 탭 포커스 창의 오른쪽에 붙이고 대상 탭을 활성 · 그 창에 포커스, 명세는 그대로다")
    void movePaneToTab() {
        // given: [t1(p1 | p2), t2(p3)], 활성 t2
        Workspace workspace = newTab(split(Workspace.initial()));

        // when
        Workspace moved = ok(workspace.movePaneToTab(P2, T2));

        // then
        assertThat(tab(moved, T1).root()).isEqualTo(new LayoutNode.Leaf(P1));
        assertThat(tab(moved, T1).focused()).isEqualTo(P1);
        assertThat(tab(moved, T2).root()).isEqualTo(row(P3, P2));
        assertThat(moved.tab().id()).isEqualTo(T2);
        assertThat(moved.tab().focused()).isEqualTo(P2);
        assertThat(moved.specs()).isEqualTo(workspace.specs());
    }

    @Test
    @DisplayName("탭의 유일한 창을 다른 탭으로 옮기면 빈 탭은 닫힌다")
    void movePaneToTabClosesEmptiedTab() {
        // given: [t1(p1), t2(p2)], 활성 t2
        Workspace workspace = newTab(Workspace.initial());

        // when
        Workspace moved = ok(workspace.movePaneToTab(P1, T2));

        // then
        assertThat(ids(moved)).containsExactly(T2);
        assertThat(moved.tab().root()).isEqualTo(row(P2, P1));
        assertThat(moved.specs()).isEqualTo(workspace.specs());
    }

    @Test
    @DisplayName("movePaneToTab 의 오류: 같은 탭 SameTab · 없는 탭 UnknownTab · 없는 창 UnknownPane")
    void movePaneToTabErrors() {
        // given
        Workspace workspace = newTab(Workspace.initial());

        // then
        assertThat(workspace.movePaneToTab(P2, T2).error()).contains(new TabError.SameTab(T2));
        assertThat(workspace.movePaneToTab(P1, T9).error()).contains(new TabError.UnknownTab(T9));
        assertThat(workspace.movePaneToTab(P9, T1).error()).contains(new TabError.UnknownPane(P9));
    }

    @Test
    @DisplayName("movePaneToNewTab 은 창을 맨 뒤 새 탭으로 옮겨 활성화하고, 탭의 유일한 창이면 SolePane 이다")
    void movePaneToNewTab() {
        // given: t1(p1 | p2), 포커스 p2
        Workspace workspace = split(Workspace.initial());

        // when
        Workspace moved = ok(workspace.movePaneToNewTab(P2));

        // then
        assertThat(ids(moved)).containsExactly(T1, T2);
        assertThat(tab(moved, T1).root()).isEqualTo(new LayoutNode.Leaf(P1));
        assertThat(moved.tab().id()).isEqualTo(T2);
        assertThat(moved.tab().root()).isEqualTo(new LayoutNode.Leaf(P2));
        assertThat(moved.specs()).isEqualTo(workspace.specs());
        assertThat(moved.nextTab()).isEqualTo(3);
        assertThat(Workspace.initial().movePaneToNewTab(P1).error()).contains(new TabError.SolePane(P1));
        assertThat(workspace.movePaneToNewTab(P9).error()).contains(new TabError.UnknownPane(P9));
    }

    @Test
    @DisplayName("tabOf 는 창이 든 탭을 돌려주고, 없는 창이면 비어 있다")
    void tabOf() {
        // given
        Workspace workspace = newTab(Workspace.initial());

        // then
        assertThat(workspace.tabOf(P1)).contains(T1);
        assertThat(workspace.tabOf(P2)).contains(T2);
        assertThat(workspace.tabOf(P9)).isEmpty();
    }

    @Test
    @DisplayName("닫은 탭 번호는 다시 쓰지 않는다")
    void freshTabIds() {
        // given
        Workspace closed = ok(newTab(Workspace.initial()).closeTab(T2));

        // when
        Workspace again = newTab(closed);

        // then
        assertThat(ids(again)).containsExactly(T1, T3);
    }

    @Test
    @DisplayName("single 은 탭 하나짜리 작업 공간이고 다음 탭 번호는 그 탭 번호 다음이다")
    void single() {
        // given
        Tab tab = new Tab(T3, new LayoutNode.Leaf(P1), P1);

        // when
        Workspace workspace = Workspace.single(tab, Map.of(P1, PaneSpec.remembered(P1)), 2);

        // then
        assertThat(workspace.tabs()).containsExactly(tab);
        assertThat(workspace.nextTab()).isEqualTo(4);
    }

    @Test
    @DisplayName("탭이 없거나, 활성 번호가 범위 밖이거나, 탭 ID 가 겹치거나, 창이 두 탭에 있으면 만들 수 없다")
    void tabsInvariants() {
        Tab t1 = new Tab(T1, new LayoutNode.Leaf(P1), P1);
        Tab t2 = new Tab(T2, new LayoutNode.Leaf(P2), P2);
        Tab t2SameAsP1 = new Tab(T2, new LayoutNode.Leaf(P1), P1);
        Map<PaneId, PaneSpec> one = Map.of(P1, PaneSpec.remembered(P1));
        Map<PaneId, PaneSpec> two = Map.of(P1, PaneSpec.remembered(P1), P2, PaneSpec.remembered(P2));
        assertThatIllegalArgumentException().isThrownBy(() -> new Workspace(List.of(), 0, Map.of(), 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new Workspace(List.of(t1, t2), 2, two, 3, 3));
        assertThatIllegalArgumentException().isThrownBy(() -> new Workspace(List.of(t1, t2), -1, two, 3, 3));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Workspace(List.of(t1, new Tab(T1, new LayoutNode.Leaf(P2), P2)), 0, two, 3, 3));
        assertThatIllegalArgumentException().isThrownBy(() -> new Workspace(List.of(t1, t2SameAsP1), 0, one, 3, 3));
    }

    @Test
    @DisplayName("다음 창 · 탭 번호는 쓰인 p<n> · t<n> 보다 커야 한다")
    void numberInvariants() {
        Tab t1 = new Tab(T1, new LayoutNode.Leaf(P2), P2);
        Map<PaneId, PaneSpec> specs = Map.of(P2, PaneSpec.remembered(P2));
        assertThatIllegalArgumentException().isThrownBy(() -> new Workspace(List.of(t1), 0, specs, 2, 2));
        assertThatIllegalArgumentException().isThrownBy(() -> new Workspace(List.of(t1), 0, specs, 3, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new Workspace(List.of(t1), 0, specs, 3, 0));
        assertThat(new Workspace(List.of(t1), 0, specs, 3, 2).nextTab()).isEqualTo(2);

        // 번호 꼴이 아닌 ID 는 번호 규칙과 상관없다
        PaneId named = new PaneId("pane");
        Tab other = new Tab(new TabId("main"), new LayoutNode.Leaf(named), named);
        assertThat(new Workspace(List.of(other), 0, Map.of(named, PaneSpec.remembered(named)), 1, 1).tabs())
                .containsExactly(other);
    }
}
