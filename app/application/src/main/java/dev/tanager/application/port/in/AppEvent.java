package dev.tanager.application.port.in;

import dev.tanager.domain.history.WorkHistory;
import dev.tanager.domain.history.WorkspacePath;
import dev.tanager.domain.session.LiveSessions;
import dev.tanager.domain.shared.PaneId;
import java.util.Objects;

/** 명령이 상태를 바꾼 뒤 구독자에게 알리는 이벤트. 세부 변경 대신 바뀐 값을 싣는다(ADR-0012). */
public sealed interface AppEvent {

    /** 작업 경로 이력이 바뀌었다. 새 이력 전체를 싣는다. */
    record HistoryChanged(WorkHistory history) implements AppEvent {
        public HistoryChanged {
            Objects.requireNonNull(history, "history");
        }
    }

    /** 창 {@code pane} 의 셸이 {@code cwd} 로 옮겼다(셸 통합이 알린 경로). */
    record SessionCwdChanged(PaneId pane, WorkspacePath cwd) implements AppEvent {
        public SessionCwdChanged {
            Objects.requireNonNull(pane, "pane");
            Objects.requireNonNull(cwd, "cwd");
        }
    }

    /** 창별 세션 목록이 바뀌었다(띄움 · 끝남). 새 목록 전체를 싣는다. */
    record SessionsChanged(LiveSessions sessions) implements AppEvent {
        public SessionsChanged {
            Objects.requireNonNull(sessions, "sessions");
        }
    }
}
