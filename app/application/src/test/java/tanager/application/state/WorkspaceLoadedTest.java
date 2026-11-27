package tanager.application.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.in.AppEvent;
import tanager.application.port.out.WorkspaceLoadNotice;
import tanager.domain.history.HistoryPolicy;
import tanager.domain.history.WorkHistory;
import tanager.domain.workspace.Workspace;

/** 작업 공간을 읽으며 생긴 일(부분 손상 · 마이그레이션)을 상태줄 알림으로 낸다(#179). */
class WorkspaceLoadedTest {
    private final AppState start = AppState.initial(WorkHistory.empty(HistoryPolicy.DEFAULT));

    @Test
    @DisplayName("불러오기 알림마다 StoreNotice 를 하나씩 내고 상태는 그대로다")
    void eachNoticeBecomesStoreNotice() {
        // given
        var dropped = new WorkspaceLoadNotice.PanesDropped(2);
        var migrated = new WorkspaceLoadNotice.Migrated(1, 2);

        // when
        Transition<Boolean> t = new WorkspaceLoaded(List.of(migrated, dropped)).apply(start);

        // then
        assertThat(t.next()).isSameAs(start);
        assertThat(t.events())
                .containsExactly(
                        new AppEvent.StoreNotice(new AppEvent.StoreProblem.WorkspaceLoaded(migrated)),
                        new AppEvent.StoreNotice(new AppEvent.StoreProblem.WorkspaceLoaded(dropped)));
    }

    @Test
    @DisplayName("알림이 없으면 이벤트도 없다")
    void noNoticesNoEvents() {
        // when
        Transition<Boolean> t = new WorkspaceLoaded(List.of()).apply(start);

        // then
        assertThat(t.events()).isEmpty();
    }

    @Test
    @DisplayName("시작 상태의 작업 공간은 복원 계획으로 정할 수 있다")
    void initialStateTakesPlannedWorkspace() {
        // given
        Workspace planned = Workspace.initial()
                .newTab(java.util.Optional.empty(), java.util.Optional.empty())
                .workspace();

        // when
        AppState state = AppState.initial(WorkHistory.empty(HistoryPolicy.DEFAULT), planned);

        // then
        assertThat(state.workspace()).isEqualTo(planned);
        assertThat(state.store()).isEqualTo(new HistoryStore.Loading(List.of()));
    }
}
