package tanager.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.in.AppEvent;
import tanager.application.port.in.RecordWindowStateUseCase;
import tanager.application.state.AppState;
import tanager.application.state.ManualExecutor;
import tanager.application.state.StateActor;
import tanager.domain.history.HistoryPolicy;
import tanager.domain.history.WorkHistory;
import tanager.domain.workspace.Bounds;
import tanager.domain.workspace.PanelState;
import tanager.domain.workspace.WindowState;

/** 메인 창 상태를 상태에 기록하고 바뀌면 WindowChanged 를 낸다(FR-RES-03, #180). */
class WindowRecorderTest {
    private static final WindowState MOVED = new WindowState(
            Optional.of(new Bounds(100, 80, 1280, 760)), false, new PanelState(true, 236), new PanelState(true, 264));

    private final ManualExecutor executor = new ManualExecutor();
    private final StateActor actor =
            new StateActor(AppState.initial(WorkHistory.empty(HistoryPolicy.DEFAULT)), executor);
    private final List<AppEvent> events = new ArrayList<>();
    private final RecordWindowStateUseCase recorder = new WindowRecorder(actor);

    WindowRecorderTest() {
        actor.subscribe(events::add);
    }

    @Test
    @DisplayName("처음 상태의 창 상태는 기본값이다")
    void initialWindowIsDefault() {
        assertThat(actor.snapshot().window()).isEqualTo(WindowState.DEFAULT);
    }

    @Test
    @DisplayName("창을 옮겨 기록하면 상태의 창 상태가 바뀌고 WindowChanged 가 한 번 나간다")
    void recordChangesStateAndAnnounces() {
        // when
        CompletableFuture<Void> reply = recorder.record(MOVED).toCompletableFuture();
        executor.runAll();

        // then
        assertThat(reply).isCompleted();
        assertThat(actor.snapshot().window()).isEqualTo(MOVED);
        assertThat(events).containsExactly(new AppEvent.WindowChanged(MOVED));
    }

    @Test
    @DisplayName("같은 창 상태를 다시 기록하면 이벤트가 없다")
    void sameStateIsNotAnnouncedAgain() {
        // given
        recorder.record(MOVED);
        executor.runAll();
        events.clear();

        // when
        CompletableFuture<Void> reply = recorder.record(MOVED).toCompletableFuture();
        executor.runAll();

        // then
        assertThat(reply).isCompleted();
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("기본값과 같은 창 상태를 기록하면 바뀐 것이 없다")
    void defaultIsNotAChange() {
        // when
        recorder.record(WindowState.DEFAULT);
        executor.runAll();

        // then
        assertThat(events).isEmpty();
    }
}
