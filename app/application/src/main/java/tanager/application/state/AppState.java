package tanager.application.state;

import java.util.List;
import java.util.Objects;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkHistory;
import tanager.domain.session.LiveSessions;

/**
 * {@link StateActor} 가 들고 있는 애플리케이션 상태(불변). 바꾸는 메서드는 새 값을 돌려준다.
 *
 * @param environments 셸 탐지 순서의 환경들(트리의 환경 노드 순서). 탐지 전에는 비어 있다 — 트리는 이력의 환경을 이름순으로 놓는다
 */
public record AppState(
        WorkHistory history, LiveSessions sessions, HistoryStore store, List<ShellEnvironment> environments) {

    public AppState {
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(sessions, "sessions");
        Objects.requireNonNull(store, "store");
        environments = List.copyOf(environments);
    }

    /** 앱 시작 때의 상태. 떠 있는 세션은 없고 이력은 아직 불러오는 중이다. */
    public static AppState initial(WorkHistory history) {
        return new AppState(history, LiveSessions.empty(), new HistoryStore.Loading(List.of()), List.of());
    }

    /** 이력만 바꾼 새 상태. */
    public AppState withHistory(WorkHistory next) {
        return new AppState(next, sessions, store, environments);
    }

    /** 세션들만 바꾼 새 상태. */
    public AppState withSessions(LiveSessions next) {
        return new AppState(history, next, store, environments);
    }

    /** 환경 순서만 바꾼 새 상태. */
    public AppState withEnvironments(List<ShellEnvironment> next) {
        return new AppState(history, sessions, store, next);
    }

    /** 저장소 상태만 바꾼 새 상태. */
    public AppState withStore(HistoryStore next) {
        return new AppState(history, sessions, next, environments);
    }
}
