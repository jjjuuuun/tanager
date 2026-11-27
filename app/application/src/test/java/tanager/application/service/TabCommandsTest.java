package tanager.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.in.AppEvent;
import tanager.application.state.AppState;
import tanager.application.state.Transition;
import tanager.domain.history.HistoryPolicy;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkHistory;
import tanager.domain.history.WorkspacePath;
import tanager.domain.layout.LayoutNode;
import tanager.domain.layout.Orientation;
import tanager.domain.layout.Ratio;
import tanager.domain.session.IntegrationMode;
import tanager.domain.session.LaunchTemplate;
import tanager.domain.session.LiveSessions;
import tanager.domain.session.ProfileId;
import tanager.domain.session.ShellKind;
import tanager.domain.session.ShellProfile;
import tanager.domain.session.TerminalSession;
import tanager.domain.session.WorkingDir;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;
import tanager.domain.shared.TabId;
import tanager.domain.workspace.PaneSpec;
import tanager.domain.workspace.Tab;
import tanager.domain.workspace.TabError;
import tanager.domain.workspace.TabName;
import tanager.domain.workspace.TabNameProblem;
import tanager.domain.workspace.Workspace;

/** 탭 명령(#177, FR-LAY-07 · 10 · 12): 액터 없이 상태 → 전이를 계산한다(ADR-0012). */
class TabCommandsTest {
    private static final ShellProfile CMD = new ShellProfile(
            new ProfileId("cmd"),
            "cmd",
            ShellKind.CMD,
            new ShellEnvironment.Windows(),
            new LaunchTemplate("cmd.exe", List.of()),
            IntegrationMode.NONE);
    private static final WorkspacePath A =
            WorkspacePath.of(new ShellEnvironment.Windows(), "C:\\a").value().orElseThrow();
    private static final PaneId P1 = new PaneId("p1");
    private static final PaneId P2 = new PaneId("p2");
    private static final PaneId P3 = new PaneId("p3");
    private static final TabId T1 = new TabId("t1");
    private static final TabId T2 = new TabId("t2");
    private static final TabId T9 = new TabId("t9");
    private static final AppState START = AppState.initial(WorkHistory.empty(HistoryPolicy.DEFAULT));

    /** [t1(p1 | p2), t2(p3)], 활성 t1 · 포커스 p1. */
    private static Workspace twoTabs() {
        Workspace split = Workspace.initial()
                .split(Orientation.ROW, Optional.empty(), Optional.of(A))
                .workspace();
        return split.newTab(Optional.empty(), Optional.empty())
                .workspace()
                .focus(P1)
                .value()
                .orElseThrow();
    }

    private static LiveSessions running(PaneId... panes) {
        LiveSessions sessions = LiveSessions.empty();
        for (PaneId pane : panes) {
            TerminalSession session = TerminalSession.start(CMD, new WorkingDir.At(A))
                    .value()
                    .orElseThrow()
                    .launched(42)
                    .value()
                    .orElseThrow();
            sessions = sessions.launched(pane, session);
        }
        return sessions;
    }

    private static List<TabId> ids(Workspace workspace) {
        return workspace.tabs().stream().map(Tab::id).toList();
    }

    private static Workspace ok(Transition<Result<Workspace, TabError>> transition) {
        return transition
                .reply()
                .value()
                .orElseThrow(
                        () -> new AssertionError("오류: " + transition.reply().error()));
    }

    /** 실패 · 바뀐 게 없음: 상태는 같은 인스턴스, 이벤트는 없다. */
    private static void assertUnchanged(Transition<?> transition, AppState before) {
        assertThat(transition.next()).isSameAs(before);
        assertThat(transition.events()).isEmpty();
    }

    @Test
    @DisplayName("새 탭: 탭이 2개 · 새 탭이 활성 · 새 창 명세는 기억한 셸, LayoutChanged 한 번")
    void create() {
        // when
        Transition<Result<Workspace, TabError>> t = TabCommands.create().apply(START);

        // then
        Workspace next = ok(t);
        assertThat(ids(next)).containsExactly(T1, T2);
        assertThat(next.tab().id()).isEqualTo(T2);
        assertThat(next.tab().root()).isEqualTo(new LayoutNode.Leaf(P2));
        assertThat(next.spec(P2)).contains(PaneSpec.remembered(P2));
        assertThat(t.next().workspace()).isEqualTo(next);
        assertThat(t.events()).containsExactly(new AppEvent.LayoutChanged(next));
    }

    @Test
    @DisplayName("탭 닫기: LayoutChanged 다음 SessionsChanged(그 탭 창들의 세션이 빠짐), 응답은 남은 탭 [t2]")
    void closeRemovesTabAndItsSessions() {
        // given
        AppState given = START.withWorkspace(twoTabs()).withSessions(running(P1, P2, P3));

        // when
        Transition<Result<Workspace, TabError>> t = TabCommands.close(T1).apply(given);

        // then
        Workspace next = ok(t);
        assertThat(ids(next)).containsExactly(T2);
        assertThat(next.specs()).containsOnlyKeys(P3);
        LiveSessions sessions = t.next().sessions();
        assertThat(sessions.byPane()).containsOnlyKeys(P3);
        assertThat(t.next().workspace()).isEqualTo(next);
        assertThat(t.events())
                .containsExactly(new AppEvent.LayoutChanged(next), new AppEvent.SessionsChanged(sessions));
    }

    @Test
    @DisplayName("마지막 탭 닫기: LastPaneClosed 를 내고 작업 공간은 그대로, 응답은 LastTab")
    void closeLastTab() {
        // when
        Transition<Result<Workspace, TabError>> t = TabCommands.close(T1).apply(START);

        // then
        assertThat(t.reply().error()).contains(new TabError.LastTab(T1));
        assertThat(t.next()).isSameAs(START);
        assertThat(t.events()).containsExactly(new AppEvent.LastPaneClosed());
    }

    @Test
    @DisplayName("없는 탭 닫기 · 고르기 · 옮기기 · 이름은 UnknownTab, 이벤트가 없다")
    void unknownTab() {
        // given
        AppState given = START.withWorkspace(twoTabs());

        // when
        List<Transition<Result<Workspace, TabError>>> all = List.of(
                TabCommands.close(T9).apply(given),
                TabCommands.select(T9).apply(given),
                TabCommands.move(T9, 0).apply(given),
                TabCommands.rename(T9, Optional.of("이름")).apply(given),
                TabCommands.moveToTab(P1, T9).apply(given));

        // then
        for (Transition<Result<Workspace, TabError>> t : all) {
            assertThat(t.reply().error()).contains(new TabError.UnknownTab(T9));
            assertUnchanged(t, given);
        }
    }

    @Test
    @DisplayName("이름 바꾸기: 공백뿐이면 InvalidName(Blank) 이고 이벤트가 없다")
    void renameBlank() {
        // when
        Transition<Result<Workspace, TabError>> t =
                TabCommands.rename(T1, Optional.of("  ")).apply(START);

        // then
        assertThat(t.reply().error()).contains(new TabError.InvalidName(new TabNameProblem.Blank()));
        assertUnchanged(t, START);
    }

    @Test
    @DisplayName("이름 바꾸기: 앞뒤 공백을 뗀 이름을 붙이고 LayoutChanged, 같은 이름 · 자동 이름 그대로면 이벤트가 없다")
    void rename() {
        // when
        Transition<Result<Workspace, TabError>> named =
                TabCommands.rename(T1, Optional.of(" 작업 ")).apply(START);
        Transition<Result<Workspace, TabError>> again =
                TabCommands.rename(T1, Optional.of("작업")).apply(named.next());
        Transition<Result<Workspace, TabError>> auto =
                TabCommands.rename(T1, Optional.empty()).apply(START);

        // then
        Workspace next = ok(named);
        assertThat(next.tab().name()).contains(new TabName("작업"));
        assertThat(named.events()).containsExactly(new AppEvent.LayoutChanged(next));
        assertUnchanged(again, named.next());
        assertUnchanged(auto, START);
    }

    @Test
    @DisplayName("이름을 비우면 자동 이름으로 돌아가고 LayoutChanged")
    void renameToAuto() {
        // given
        AppState named = TabCommands.rename(T1, Optional.of("작업")).apply(START).next();

        // when
        Transition<Result<Workspace, TabError>> t =
                TabCommands.rename(T1, Optional.empty()).apply(named);

        // then
        Workspace next = ok(t);
        assertThat(next.tab().name()).isEmpty();
        assertThat(t.events()).containsExactly(new AppEvent.LayoutChanged(next));
    }

    @Test
    @DisplayName("활성 탭을 다시 고르면 이벤트가 없고, 다른 탭을 고르면 LayoutChanged")
    void select() {
        // given
        AppState given = START.withWorkspace(twoTabs());

        // when
        Transition<Result<Workspace, TabError>> same = TabCommands.select(T1).apply(given);
        Transition<Result<Workspace, TabError>> other = TabCommands.select(T2).apply(given);

        // then
        assertUnchanged(same, given);
        Workspace next = ok(other);
        assertThat(next.tab().id()).isEqualTo(T2);
        assertThat(other.events()).containsExactly(new AppEvent.LayoutChanged(next));
    }

    @Test
    @DisplayName("옆 탭 고르기는 양 끝에서 돌고, 탭이 하나면 이벤트가 없다")
    void selectRelative() {
        // given
        AppState given = START.withWorkspace(twoTabs());

        // when
        Transition<Result<Workspace, TabError>> left =
                TabCommands.selectRelative(-1).apply(given);
        Transition<Result<Workspace, TabError>> sole =
                TabCommands.selectRelative(1).apply(START);

        // then
        Workspace next = ok(left);
        assertThat(next.tab().id()).isEqualTo(T2);
        assertThat(left.events()).containsExactly(new AppEvent.LayoutChanged(next));
        assertUnchanged(sole, START);
    }

    @Test
    @DisplayName("번호로 고르기: 있는 번호면 그 탭, 없는 번호면 NoSuchIndex 이고 이벤트가 없다")
    void selectIndex() {
        // given
        AppState given = START.withWorkspace(twoTabs());

        // when
        Transition<Result<Workspace, TabError>> second =
                TabCommands.selectIndex(1).apply(given);
        Transition<Result<Workspace, TabError>> missing =
                TabCommands.selectIndex(5).apply(given);

        // then
        assertThat(ok(second).tab().id()).isEqualTo(T2);
        assertThat(second.events()).hasSize(1);
        assertThat(missing.reply().error()).contains(new TabError.NoSuchIndex(5));
        assertUnchanged(missing, given);
    }

    @Test
    @DisplayName("탭 순서 옮기기: 자리가 바뀌면 LayoutChanged, 같은 자리면 이벤트가 없다")
    void move() {
        // given
        AppState given = START.withWorkspace(twoTabs());

        // when
        Transition<Result<Workspace, TabError>> moved = TabCommands.move(T1, 1).apply(given);
        Transition<Result<Workspace, TabError>> stay = TabCommands.move(T1, 0).apply(given);

        // then
        Workspace next = ok(moved);
        assertThat(ids(next)).containsExactly(T2, T1);
        assertThat(next.tab().id()).isEqualTo(T1);
        assertThat(moved.events()).containsExactly(new AppEvent.LayoutChanged(next));
        assertUnchanged(stay, given);
    }

    @Test
    @DisplayName("창을 다른 탭 위에 놓으면 그 탭이 활성 · 포커스는 옮긴 창, 명세는 같다, LayoutChanged 한 번")
    void moveToTab() {
        // given
        AppState given = START.withWorkspace(twoTabs());
        PaneSpec before = given.workspace().spec(P2).orElseThrow();

        // when
        Transition<Result<Workspace, TabError>> t =
                TabCommands.moveToTab(P2, T2).apply(given);

        // then
        Workspace next = ok(t);
        assertThat(next.tab().id()).isEqualTo(T2);
        assertThat(next.tab().focused()).isEqualTo(P2);
        assertThat(next.tab().root())
                .isEqualTo(new LayoutNode.Split(
                        Orientation.ROW, Ratio.HALF, new LayoutNode.Leaf(P3), new LayoutNode.Leaf(P2)));
        assertThat(next.spec(P2)).contains(before);
        assertThat(t.events()).containsExactly(new AppEvent.LayoutChanged(next));
    }

    @Test
    @DisplayName("창이 이미 그 탭에 있으면 SameTab 이고 이벤트가 없다")
    void moveToSameTab() {
        // given
        AppState given = START.withWorkspace(twoTabs());

        // when
        Transition<Result<Workspace, TabError>> t =
                TabCommands.moveToTab(P1, T1).apply(given);

        // then
        assertThat(t.reply().error()).contains(new TabError.SameTab(T1));
        assertUnchanged(t, given);
    }

    @Test
    @DisplayName("창을 새 탭으로 옮기면 맨 뒤에 그 창 하나짜리 탭이 생겨 활성, 명세는 같다, LayoutChanged 한 번")
    void moveToNewTab() {
        // given
        AppState given = START.withWorkspace(twoTabs());
        PaneSpec before = given.workspace().spec(P2).orElseThrow();

        // when
        Transition<Result<Workspace, TabError>> t = TabCommands.moveToNewTab(P2).apply(given);

        // then
        Workspace next = ok(t);
        assertThat(ids(next)).containsExactly(T1, T2, new TabId("t3"));
        assertThat(next.tab().root()).isEqualTo(new LayoutNode.Leaf(P2));
        assertThat(next.spec(P2)).contains(before);
        assertThat(t.events()).containsExactly(new AppEvent.LayoutChanged(next));
    }

    @Test
    @DisplayName("탭의 유일한 창을 새 탭으로 옮기면 SolePane, 없는 창이면 UnknownPane — 이벤트가 없다")
    void moveToNewTabFailures() {
        // given
        AppState given = START.withWorkspace(twoTabs());
        PaneId gone = new PaneId("p9");

        // when
        Transition<Result<Workspace, TabError>> sole =
                TabCommands.moveToNewTab(P3).apply(given);
        Transition<Result<Workspace, TabError>> unknown =
                TabCommands.moveToNewTab(gone).apply(given);

        // then
        assertThat(sole.reply().error()).contains(new TabError.SolePane(P3));
        assertUnchanged(sole, given);
        assertThat(unknown.reply().error()).contains(new TabError.UnknownPane(gone));
        assertUnchanged(unknown, given);
    }
}
