package tanager.application.service;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import tanager.application.port.in.AppEvent;
import tanager.application.port.out.HistoryRepository;
import tanager.application.port.out.HistoryStoreError;
import tanager.application.port.out.Scheduler;
import tanager.application.state.HistoryStore;
import tanager.application.state.StateActor;
import tanager.application.state.Transition;
import tanager.domain.history.WorkHistory;

/**
 * 이력이 바뀌면 <b>첫 미저장 변경 후 1초</b>에 저장한다(throttle, ADR-0013 · FR-RES-01). 일정 조율은 {@link ThrottledSaver} 가 한다.
 * 저장소가 쓸 수 있는 상태일 때만 저장한다 — 불러오는 중에 빈 이력으로 파일을 덮지 않는다. 불러오기만 한 이력은 파일과 같아 쓰지 않는다.
 *
 * <p>불러오는 중인지 · 그동안 방문이 있었는지는 {@link #monitor} 로 지킨다(ADR-0013 개정 — 출력 포트 일정 조율용 가변 상태).
 */
public final class HistorySaver {
    private final StateActor state;
    private final ThrottledSaver<WorkHistory> saver;
    private final Object monitor = new Object();

    // 아래는 monitor 로 지킨다
    private boolean loading;
    private boolean visitedWhileLoading;

    /**
     * 만들면서 {@code state} 의 이벤트를 구독한다. 다른 스레드가 {@code state} 에 명령(방문 · 불러오기)을 내기 전에 만든다 — 스냅샷을 읽은 뒤 구독하기 전에 확정된 전이는 보지 못한다. 만들 때 이미 쌓여 있던 방문(불러오는 중의 방문)도 불러온 뒤
     * 저장할 것으로 센다.
     */
    public HistorySaver(StateActor state, HistoryRepository repository, Scheduler scheduler, Executor io) {
        this.state = Objects.requireNonNull(state, "state");
        Objects.requireNonNull(repository, "repository");
        this.saver = new ThrottledSaver<>(
                "이력",
                scheduler,
                io,
                () -> state.snapshot().history(),
                repository::save,
                () -> state.snapshot().store() instanceof HistoryStore.Writable,
                this::announce);
        HistoryStore store = state.snapshot().store();
        synchronized (monitor) {
            this.loading = store instanceof HistoryStore.Loading;
            this.visitedWhileLoading =
                    store instanceof HistoryStore.Loading l && !l.pending().isEmpty();
        }
        state.subscribe(this::onEvent);
    }

    /**
     * 예약을 거두고 미저장 변경을 부른 스레드에서 바로 저장한다(종료 때, ADR-0012). 진행 중인 저장은 {@code timeout} 까지 기다린다. 쓸 수
     * 있는 상태가 아니면 저장하지 않는다.
     *
     * @return 저장할 것이 없었거나 저장했으면 {@code true}. 쓸 수 없는 상태(ReadOnly · Loading)여서 저장하지 않았을 때도 {@code
     *     true}. 진행 중 저장이 제때 끝나지 않았거나 저장이 실패했으면 {@code false}
     */
    public boolean flush(Duration timeout) {
        return saver.flush(timeout);
    }

    // actor 스레드. 콜백 시점의 snapshot 이 이 이벤트를 낸 전이의 결과다
    private void onEvent(AppEvent event) {
        if (!(event instanceof AppEvent.HistoryChanged(WorkHistory history))) {
            return;
        }
        HistoryStore store = state.snapshot().store();
        synchronized (monitor) {
            switch (store) {
                case HistoryStore.Loading(var pending) -> {
                    loading = true;
                    visitedWhileLoading = !pending.isEmpty();
                }
                case HistoryStore.ReadOnly(var _) -> loading = false;
                case HistoryStore.Writable() -> changed(history);
            }
        }
    }

    // monitor 안. 불러오기만 한 이력은 파일과 같다 — 불러오는 동안 방문이 있었을 때만 저장할 것이 있다
    private void changed(WorkHistory history) {
        if (loading) {
            loading = false;
            if (!visitedWhileLoading) {
                saver.known(history);
            }
        }
        saver.changed(history);
    }

    private void announce(HistoryStoreError error) {
        state.submit(current -> new Transition<>(
                current, List.of(new AppEvent.StoreNotice(new AppEvent.StoreProblem.SaveFailed(error))), true));
    }
}
