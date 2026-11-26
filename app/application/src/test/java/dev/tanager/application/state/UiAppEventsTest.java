package dev.tanager.application.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.tanager.application.port.in.AppEvent;
import dev.tanager.application.port.out.QueuedUiDispatcher;
import dev.tanager.application.port.out.Subscription;
import dev.tanager.domain.history.HistoryPolicy;
import dev.tanager.domain.history.ShellEnvironment;
import dev.tanager.domain.history.WorkHistory;
import dev.tanager.domain.history.WorkspacePath;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** UI 쪽 실행이 나중에 오는 경우(EDT 큐)의 구독 해제 · 예외 격리. */
class UiAppEventsTest {
    private static final WorkspacePath HOME =
            WorkspacePath.of(new ShellEnvironment.Windows(), "C:\\work").value().orElseThrow();

    private final ManualExecutor executor = new ManualExecutor();
    private final StateActor actor =
            new StateActor(AppState.initial(WorkHistory.empty(HistoryPolicy.DEFAULT)), executor);
    private final QueuedUiDispatcher ui = new QueuedUiDispatcher();
    private final UiAppEvents events = new UiAppEvents(actor, ui);

    private static Command<Integer> visit() {
        return current -> {
            WorkHistory next = current.history().recordVisit(HOME, Instant.EPOCH);
            return new Transition<>(
                    current.withHistory(next),
                    List.of(new AppEvent.HistoryChanged(next)),
                    next.entries().get(HOME).visitCount());
        };
    }

    @Test
    @DisplayName("UI 큐에 이미 들어간 이벤트도 해제한 뒤에는 콜백으로 오지 않는다")
    void cancelBeforeUiRunsSuppressesQueuedCallback() {
        // given
        var received = new ArrayList<AppEvent>();
        Subscription subscription = events.subscribeOnUi(received::add);
        actor.submit(visit());
        executor.runAll();
        assertThat(ui.pending()).isEqualTo(1);

        // when
        subscription.cancel();
        ui.runAll();

        // then
        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("UI 콜백이 던지면 UI 쪽에서 잡고 그 구독만 해제한다 — 큐에 남은 이벤트와 이후 이벤트도 오지 않는다")
    void throwingUiCallbackIsCaughtAndUnsubscribed() {
        // given
        var badCalls = new ArrayList<AppEvent>();
        var good = new ArrayList<AppEvent>();
        events.subscribeOnUi(e -> {
            badCalls.add(e);
            throw new IllegalStateException("UI 버그");
        });
        events.subscribeOnUi(good::add);
        actor.submit(visit());
        actor.submit(visit());
        executor.runAll();

        // when / then
        assertThatCode(ui::runAll).doesNotThrowAnyException();
        assertThat(badCalls).hasSize(1);
        assertThat(good).hasSize(2);

        // when
        actor.submit(visit());
        executor.runAll();
        ui.runAll();

        // then
        assertThat(badCalls).hasSize(1);
        assertThat(good).hasSize(3);
    }
}
