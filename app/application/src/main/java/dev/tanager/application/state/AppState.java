package dev.tanager.application.state;

import dev.tanager.domain.history.WorkHistory;
import dev.tanager.domain.session.LiveSessions;
import java.util.Objects;

/** {@link StateActor} 가 들고 있는 애플리케이션 상태(불변). 바꾸는 메서드는 새 값을 돌려준다. */
public record AppState(WorkHistory history, LiveSessions sessions) {

    public AppState {
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(sessions, "sessions");
    }

    /** 앱 시작 때의 상태. 떠 있는 세션은 없다. */
    public static AppState initial(WorkHistory history) {
        return new AppState(history, LiveSessions.empty());
    }

    /** 이력만 바꾼 새 상태. */
    public AppState withHistory(WorkHistory next) {
        return new AppState(next, sessions);
    }

    /** 세션들만 바꾼 새 상태. */
    public AppState withSessions(LiveSessions next) {
        return new AppState(history, next);
    }
}
