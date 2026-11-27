package tanager.application.state;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tanager.application.port.out.AppConfig;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkHistory;
import tanager.domain.history.WorkspacePath;
import tanager.domain.session.LiveSessions;
import tanager.domain.workspace.Workspace;

/**
 * {@link StateActor} 가 들고 있는 애플리케이션 상태(불변). 바꾸는 메서드는 새 값을 돌려준다.
 *
 * @param environments 셸 탐지 순서의 환경들(트리의 환경 노드 순서). 탐지 전에는 비어 있다 — 트리는 이력의 환경을 이름순으로 놓는다
 * @param workspace 창 배치와 창별 시작 명세(#88). UI 는 이 값을 그리기만 한다
 * @param settings 마지막으로 읽은 정상 설정(FR-CFG-03). 처음 읽기 전에는 비어 있다
 * @param missing 더 이상 없다고 확인된 이력 경로(FR-HIS-08). 저장하지 않는다 — 모르는 경로는 들지 않는다
 */
public record AppState(
        WorkHistory history,
        LiveSessions sessions,
        HistoryStore store,
        List<ShellEnvironment> environments,
        Workspace workspace,
        Optional<AppConfig> settings,
        Set<WorkspacePath> missing) {

    public AppState {
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(sessions, "sessions");
        Objects.requireNonNull(store, "store");
        environments = List.copyOf(environments);
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(settings, "settings");
        missing = Set.copyOf(missing);
    }

    /** 앱 시작 때의 상태. 떠 있는 세션은 없고 이력 · 설정은 아직 읽는 중이며, 창은 p1 하나다. */
    public static AppState initial(WorkHistory history) {
        return initial(history, Workspace.initial());
    }

    /** 앱 시작 때의 상태. 창은 복원 계획({@code RestoreWorkspaceUseCase.plan}) 대로다(#179). */
    public static AppState initial(WorkHistory history, Workspace workspace) {
        return new AppState(
                history,
                LiveSessions.empty(),
                new HistoryStore.Loading(List.of()),
                List.of(),
                workspace,
                Optional.empty(),
                Set.of());
    }

    /** 이력만 바꾼 새 상태. */
    public AppState withHistory(WorkHistory next) {
        return new AppState(next, sessions, store, environments, workspace, settings, missing);
    }

    /** 세션들만 바꾼 새 상태. */
    public AppState withSessions(LiveSessions next) {
        return new AppState(history, next, store, environments, workspace, settings, missing);
    }

    /** 환경 순서만 바꾼 새 상태. */
    public AppState withEnvironments(List<ShellEnvironment> next) {
        return new AppState(history, sessions, store, next, workspace, settings, missing);
    }

    /** 저장소 상태만 바꾼 새 상태. */
    public AppState withStore(HistoryStore next) {
        return new AppState(history, sessions, next, environments, workspace, settings, missing);
    }

    /** 작업 공간만 바꾼 새 상태. */
    public AppState withWorkspace(Workspace next) {
        return new AppState(history, sessions, store, environments, next, settings, missing);
    }

    /** 설정만 바꾼 새 상태. */
    public AppState withSettings(AppConfig next) {
        return new AppState(history, sessions, store, environments, workspace, Optional.of(next), missing);
    }

    /** 사라진 경로 집합만 바꾼 새 상태. */
    public AppState withMissing(Set<WorkspacePath> next) {
        return new AppState(history, sessions, store, environments, workspace, settings, next);
    }
}
