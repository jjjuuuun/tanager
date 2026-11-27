package tanager.application.service;

import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tanager.application.port.in.AppEvent;
import tanager.application.port.in.RecordVisitUseCase;
import tanager.application.state.AppState;
import tanager.application.state.HistoryStore;
import tanager.application.state.PendingVisit;
import tanager.application.state.StateActor;
import tanager.application.state.Transition;
import tanager.domain.history.WorkHistory;
import tanager.domain.history.WorkspacePath;
import tanager.domain.session.LiveSessions;
import tanager.domain.shared.PaneId;

/**
 * 셸이 알린 경로를 명령 하나로 세션 · 이력에 함께 적용한다(FR-HIS-01, ADR-0012 · 0015). 같은 명령 안에서 바꿔야 "그 창이 마지막으로 알린
 * 경로" 판정과 방문 기록이 어긋나지 않는다.
 */
public final class VisitRecorder implements RecordVisitUseCase {
    private final StateActor state;
    private final InstantSource clock;

    public VisitRecorder(StateActor state, InstantSource clock) {
        this.state = Objects.requireNonNull(state, "state");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // 읽기 스레드에서 불린다 — 응답을 기다리지 않는다. 방문 시각은 보고가 온 때다
    @Override
    public void record(PaneId pane, WorkspacePath cwd) {
        Objects.requireNonNull(pane, "pane");
        Objects.requireNonNull(cwd, "cwd");
        Instant at = clock.instant();
        state.submit(current -> visit(current, pane, cwd, at));
    }

    // 불러오기 전 방문은 불러온 이력 위에 다시 적용하려고 쌓아 둔다(ADR-0013)
    private static HistoryStore queued(HistoryStore store, WorkspacePath cwd, Instant at) {
        return switch (store) {
            case HistoryStore.Loading loading -> loading.queued(new PendingVisit(cwd, at));
            case HistoryStore.Writable writable -> writable;
            case HistoryStore.ReadOnly readOnly -> readOnly;
        };
    }

    private static Transition<Boolean> visit(AppState current, PaneId pane, WorkspacePath cwd, Instant at) {
        Optional<LiveSessions> moved = current.sessions().cwdReported(pane, cwd);
        if (moved.isEmpty()) {
            return new Transition<>(current, List.of(), false);
        }
        WorkHistory history = current.history().recordVisit(cwd, at);
        return new Transition<>(
                current.withHistory(history).withSessions(moved.get()).withStore(queued(current.store(), cwd, at)),
                List.of(new AppEvent.HistoryChanged(history), new AppEvent.SessionCwdChanged(pane, cwd)),
                true);
    }
}
