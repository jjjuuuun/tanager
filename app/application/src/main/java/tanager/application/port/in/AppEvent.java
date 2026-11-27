package tanager.application.port.in;

import java.util.Objects;
import tanager.domain.history.WorkHistory;
import tanager.domain.history.WorkspacePath;
import tanager.domain.session.LiveSessions;
import tanager.domain.shared.PaneId;

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

    /** 이력 저장소에 사용자에게 알릴 일이 생겼다(불러오기 복구 · 읽기 전용 · 저장 실패 등). 상태줄 알림 한 줄. */
    record StoreNotice(String message) implements AppEvent {
        public StoreNotice {
            Objects.requireNonNull(message, "message");
        }
    }
}
