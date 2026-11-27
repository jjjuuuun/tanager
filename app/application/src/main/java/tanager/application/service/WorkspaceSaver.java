package tanager.application.service;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import tanager.application.port.in.AppEvent;
import tanager.application.port.out.HistoryStoreError;
import tanager.application.port.out.Scheduler;
import tanager.application.port.out.WorkspaceRepository;
import tanager.application.state.AppState;
import tanager.application.state.HistoryStore;
import tanager.application.state.StateActor;
import tanager.application.state.Transition;
import tanager.domain.workspace.WindowState;
import tanager.domain.workspace.WorkspaceSnapshot;

/**
 * 작업 공간(탭 · 분할 · 창별 프로파일 · 마지막 cwd · 포커스)을 <b>첫 미저장 변경 후 1초</b>에 저장한다(FR-RES-01, #179). 일정 조율은
 * {@link ThrottledSaver} 로 이력과 같다. 저장하는 값은 {@link WorkspaceSnapshot#capture} — 실제로 뜬 프로파일과 셸이 알린 cwd 를 명세에
 * 덮은 것이다. 레이아웃 · 세션 · cwd 가 바뀌면 다시 계산해 파일에 있는 값과 다를 때만 예약한다.
 *
 * <p>이력과 같은 문서라 저장소 상태({@link HistoryStore})가 쓸 수 있을 때만 쓴다. 불러오는 중의 변경은 불러온 뒤(이력 변경 이벤트) 다시
 * 비교해 예약한다. 창 상태는 불러온 값을 그대로 다시 쓴다 — 창 상태를 따라가는 일은 #180 이다.
 */
public final class WorkspaceSaver {
    private final StateActor state;
    private final WindowState window;
    private final ThrottledSaver<WorkspaceSnapshot> saver;

    /**
     * 만들면서 {@code state} 의 이벤트를 구독한다. 다른 스레드가 작업 공간 · 세션을 바꾸기 전에 만든다.
     *
     * @param loaded 시작할 때 읽은 작업 공간. 있으면 파일에 그 값이 있다고 알아 같으면 쓰지 않는다
     */
    public WorkspaceSaver(
            StateActor state,
            WorkspaceRepository repository,
            Scheduler scheduler,
            Executor io,
            Optional<WorkspaceSnapshot> loaded) {
        this.state = Objects.requireNonNull(state, "state");
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(loaded, "loaded");
        this.window = loaded.map(WorkspaceSnapshot::window).orElse(WindowState.DEFAULT);
        this.saver = new ThrottledSaver<>(
                "작업 공간",
                scheduler,
                io,
                () -> capture(state.snapshot()),
                repository::saveWorkspace,
                () -> state.snapshot().store() instanceof HistoryStore.Writable,
                this::announce);
        loaded.ifPresent(saver::known);
        state.subscribe(this::onEvent);
    }

    /**
     * 예약을 거두고 미저장 변경을 부른 스레드에서 바로 저장한다(종료 때). 반환 값은 {@link HistorySaver#flush} 와 같다.
     */
    public boolean flush(Duration timeout) {
        return saver.flush(timeout);
    }

    // actor 스레드. 이력 변경은 불러오기가 끝났다는 신호이기도 하다 — 불러오는 동안 바뀐 것을 그때 예약한다
    private void onEvent(AppEvent event) {
        boolean relevant = event instanceof AppEvent.LayoutChanged
                || event instanceof AppEvent.SessionsChanged
                || event instanceof AppEvent.SessionCwdChanged
                || event instanceof AppEvent.HistoryChanged;
        AppState current = state.snapshot();
        // 읽기 전용이면 예약도 하지 않는다 — 쓰지 않을 것을 1초마다 깨우지 않게
        if (relevant && !(current.store() instanceof HistoryStore.ReadOnly)) {
            saver.changed(capture(current));
        }
    }

    private WorkspaceSnapshot capture(AppState current) {
        return WorkspaceSnapshot.capture(current.workspace(), current.sessions(), window);
    }

    private void announce(HistoryStoreError error) {
        state.submit(current -> new Transition<>(
                current, List.of(new AppEvent.StoreNotice(new AppEvent.StoreProblem.SaveFailed(error))), true));
    }
}
