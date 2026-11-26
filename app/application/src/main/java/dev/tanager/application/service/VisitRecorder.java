package dev.tanager.application.service;

import dev.tanager.application.port.in.AppEvent;
import dev.tanager.application.port.in.RecordVisitUseCase;
import dev.tanager.application.state.AppState;
import dev.tanager.application.state.StateActor;
import dev.tanager.application.state.Transition;
import dev.tanager.domain.history.WorkHistory;
import dev.tanager.domain.history.WorkspacePath;
import dev.tanager.domain.session.LiveSessions;
import dev.tanager.domain.shared.PaneId;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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

    private static Transition<Boolean> visit(AppState current, PaneId pane, WorkspacePath cwd, Instant at) {
        Optional<LiveSessions> moved = current.sessions().cwdReported(pane, cwd);
        if (moved.isEmpty()) {
            return new Transition<>(current, List.of(), false);
        }
        WorkHistory history = current.history().recordVisit(cwd, at);
        return new Transition<>(
                current.withHistory(history).withSessions(moved.get()),
                List.of(new AppEvent.HistoryChanged(history), new AppEvent.SessionCwdChanged(pane, cwd)),
                true);
    }
}
