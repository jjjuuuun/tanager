package dev.tanager.application.state;

import dev.tanager.domain.history.WorkHistory;
import java.util.Objects;

/** {@link StateActor} 가 들고 있는 애플리케이션 상태(불변). 바꾸는 메서드는 새 값을 돌려준다. */
public record AppState(WorkHistory history) {

    public AppState {
        Objects.requireNonNull(history, "history");
    }

    /** 앱 시작 때의 상태. */
    public static AppState initial(WorkHistory history) {
        return new AppState(history);
    }

    /** 이력만 바꾼 새 상태. */
    public AppState withHistory(WorkHistory next) {
        return new AppState(next);
    }
}
