package tanager.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.in.AppEvent;
import tanager.application.port.in.ManageTabUseCase;
import tanager.application.port.in.MovePaneUseCase;
import tanager.application.port.out.InMemoryConfigRepository;
import tanager.application.state.AppState;
import tanager.application.state.StateActor;
import tanager.domain.history.HistoryPolicy;
import tanager.domain.history.PathTranslator;
import tanager.domain.history.WorkHistory;
import tanager.domain.history.WslMount;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;
import tanager.domain.shared.TabId;
import tanager.domain.workspace.Tab;
import tanager.domain.workspace.TabError;
import tanager.domain.workspace.TabName;
import tanager.domain.workspace.Workspace;

/** 탭 유스케이스가 상태 실행기를 거쳐 작업 공간을 바꾼다(#177, FR-LAY-07 · 10 · 12). 규칙 자체는 {@link TabCommandsTest}. */
class TabUseCasesTest {
    private static final PaneId P1 = new PaneId("p1");
    private static final PaneId P2 = new PaneId("p2");
    private static final TabId T1 = new TabId("t1");
    private static final TabId T2 = new TabId("t2");

    private final StateActor state =
            new StateActor(AppState.initial(WorkHistory.empty(HistoryPolicy.DEFAULT)), Runnable::run);
    private final List<AppEvent> events = new ArrayList<>();
    private final PaneManager panes = new PaneManager(
            List::of,
            new InMemoryConfigRepository(),
            (from, to) -> PathTranslator.translate(from, to, WslMount.DEFAULT),
            state);
    private final ManageTabUseCase tabs = panes;
    private final MovePaneUseCase move = panes;

    TabUseCasesTest() {
        state.subscribe(events::add);
    }

    private static <T, E> T ok(CompletionStage<Result<T, E>> stage) {
        Result<T, E> result = stage.toCompletableFuture().join();
        return result.value().orElseThrow(() -> new AssertionError("오류: " + result.error()));
    }

    private static <T, E> E err(CompletionStage<Result<T, E>> stage) {
        return stage.toCompletableFuture().join().error().orElseThrow();
    }

    private Workspace workspace() {
        return state.snapshot().workspace();
    }

    @Test
    @DisplayName("새 탭을 만들면 상태가 바뀌고 LayoutChanged 가 한 번 나간다")
    void create() {
        // when
        Workspace created = ok(tabs.create());

        // then
        assertThat(workspace()).isEqualTo(created);
        assertThat(created.tabs()).hasSize(2);
        assertThat(created.tab().id()).isEqualTo(T2);
        assertThat(events).containsExactly(new AppEvent.LayoutChanged(created));
    }

    @Test
    @DisplayName("이름 · 순서 · 고르기 · 옆 탭 · 번호가 실행기를 거쳐 상태에 남는다")
    void manage() {
        // given
        ok(tabs.create());
        events.clear();

        // when
        ok(tabs.rename(T1, Optional.of("작업")));
        ok(tabs.move(T1, 1));
        ok(tabs.select(T2));
        ok(tabs.selectRelative(1));
        Workspace last = ok(tabs.selectIndex(0));

        // then
        assertThat(workspace()).isEqualTo(last);
        assertThat(last.tabs().stream().map(Tab::id)).containsExactly(T2, T1);
        assertThat(last.tabs().get(1).name()).contains(new TabName("작업"));
        assertThat(last.tab().id()).isEqualTo(T2);
        // 이름 · 순서 · t1(옆 탭) · t2(번호) — 이미 활성인 t2 고르기는 이벤트가 없다
        assertThat(events).hasSize(4).allMatch(AppEvent.LayoutChanged.class::isInstance);
    }

    @Test
    @DisplayName("마지막 탭을 닫으면 LastPaneClosed 가 나가고 작업 공간은 그대로다")
    void closeLastTab() {
        // when
        TabError error = err(tabs.close(T1));

        // then
        assertThat(error).isEqualTo(new TabError.LastTab(T1));
        assertThat(workspace()).isEqualTo(Workspace.initial());
        assertThat(events).containsExactly(new AppEvent.LastPaneClosed());
    }

    @Test
    @DisplayName("탭을 닫으면 LayoutChanged 다음 SessionsChanged")
    void closeTab() {
        // given
        ok(tabs.create());
        events.clear();

        // when
        Workspace closed = ok(tabs.close(T2));

        // then
        assertThat(closed.tabs().stream().map(Tab::id)).containsExactly(T1);
        assertThat(events)
                .containsExactly(
                        new AppEvent.LayoutChanged(closed),
                        new AppEvent.SessionsChanged(state.snapshot().sessions()));
    }

    @Test
    @DisplayName("창을 다른 탭 · 새 탭으로 옮긴다 — 탭의 유일한 창을 새 탭으로 옮기면 SolePane 이고 이벤트가 없다")
    void movePaneBetweenTabs() {
        // given — [t1(p1), t2(p2)], 활성 t2
        ok(tabs.create());
        events.clear();

        // when
        TabError sole = err(move.moveToNewTab(P1));
        Workspace moved = ok(move.moveToTab(P1, T2));

        // then
        assertThat(sole).isEqualTo(new TabError.SolePane(P1));
        assertThat(moved.tabs().stream().map(Tab::id)).containsExactly(T2);
        assertThat(moved.tab().focused()).isEqualTo(P1);
        assertThat(events).containsExactly(new AppEvent.LayoutChanged(moved));

        // when — t2(p2 | p1) 에서 p1 을 새 탭으로
        events.clear();
        Workspace split = ok(move.moveToNewTab(P1));

        // then
        assertThat(split.tabs()).hasSize(2);
        assertThat(split.tab().root().panes()).containsExactly(P1);
        assertThat(events).containsExactly(new AppEvent.LayoutChanged(split));
        assertThat(workspace().spec(P2)).isPresent();
    }
}
