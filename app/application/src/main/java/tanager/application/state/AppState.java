package tanager.application.state;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tanager.application.port.out.AppConfig;
import tanager.domain.history.ImportOrigin;
import tanager.domain.history.PastConversation;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkHistory;
import tanager.domain.history.WorkspacePath;
import tanager.domain.session.LiveSessions;
import tanager.domain.workspace.WindowState;
import tanager.domain.workspace.Workspace;

/**
 * {@link StateActor} 가 들고 있는 애플리케이션 상태(불변). 바꾸는 메서드는 새 값을 돌려준다.
 *
 * @param environments 셸 탐지 순서의 환경들(트리의 환경 노드 순서). 탐지 전에는 비어 있다 — 트리는 이력의 환경을 이름순으로 놓는다
 * @param workspace 창 배치와 창별 시작 명세(#88). UI 는 이 값을 그리기만 한다
 * @param settings 마지막으로 읽은 정상 설정(FR-CFG-03). 처음 읽기 전에는 비어 있다
 * @param missing 더 이상 없다고 확인된 이력 경로(FR-HIS-08). 저장하지 않는다 — 모르는 경로는 들지 않는다
 * @param window 메인 창의 위치 · 크기 · 최대화 · 패널 상태(FR-RES-03, #180). 시작 때는 복원한 값, 그 뒤는 UI 가 기록한 값이다
 * @param conversations 가져온 이전 대화(FR-TK-09, #215). origin 마다 목록이고 빈 목록은 두지 않는다. 저장하지 않는다 — 요약은 개인
 *     정보라 메모리에만 둔다(SDD §7.3, ADR-0020)
 */
public record AppState(
        WorkHistory history,
        LiveSessions sessions,
        HistoryStore store,
        List<ShellEnvironment> environments,
        Workspace workspace,
        Optional<AppConfig> settings,
        Set<WorkspacePath> missing,
        WindowState window,
        Map<ImportOrigin, List<PastConversation>> conversations) {

    public AppState {
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(sessions, "sessions");
        Objects.requireNonNull(store, "store");
        environments = List.copyOf(environments);
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(settings, "settings");
        missing = Set.copyOf(missing);
        Objects.requireNonNull(window, "window");
        conversations = copyOf(conversations);
    }

    // 깊은 불변 사본. 빈 목록은 "가져온 대화 없음" 과 같으므로 뺀다 — 같은 상태가 한 모양이어야 바뀜 비교가 맞다
    private static Map<ImportOrigin, List<PastConversation>> copyOf(
            Map<ImportOrigin, List<PastConversation>> conversations) {
        Map<ImportOrigin, List<PastConversation>> copy = new HashMap<>();
        for (var e : conversations.entrySet()) {
            if (!e.getValue().isEmpty()) {
                copy.put(e.getKey(), List.copyOf(e.getValue()));
            }
        }
        return Map.copyOf(copy);
    }

    /** 앱 시작 때의 상태. 떠 있는 세션은 없고 이력 · 설정은 아직 읽는 중이며, 창은 p1 하나다. */
    public static AppState initial(WorkHistory history) {
        return initial(history, Workspace.initial());
    }

    /** 앱 시작 때의 상태. 창은 복원 계획({@code RestoreWorkspaceUseCase.plan}) 대로다(#179). 창 상태는 기본값이다. */
    public static AppState initial(WorkHistory history, Workspace workspace) {
        return new AppState(
                history,
                LiveSessions.empty(),
                new HistoryStore.Loading(List.of()),
                List.of(),
                workspace,
                Optional.empty(),
                Set.of(),
                WindowState.DEFAULT,
                Map.of());
    }

    /** 이력만 바꾼 새 상태. */
    public AppState withHistory(WorkHistory next) {
        return new AppState(next, sessions, store, environments, workspace, settings, missing, window, conversations);
    }

    /** 세션들만 바꾼 새 상태. */
    public AppState withSessions(LiveSessions next) {
        return new AppState(history, next, store, environments, workspace, settings, missing, window, conversations);
    }

    /** 환경 순서만 바꾼 새 상태. */
    public AppState withEnvironments(List<ShellEnvironment> next) {
        return new AppState(history, sessions, store, next, workspace, settings, missing, window, conversations);
    }

    /** 저장소 상태만 바꾼 새 상태. */
    public AppState withStore(HistoryStore next) {
        return new AppState(history, sessions, next, environments, workspace, settings, missing, window, conversations);
    }

    /** 작업 공간만 바꾼 새 상태. */
    public AppState withWorkspace(Workspace next) {
        return new AppState(history, sessions, store, environments, next, settings, missing, window, conversations);
    }

    /** 창 상태만 바꾼 새 상태(#180). */
    public AppState withWindow(WindowState next) {
        return new AppState(history, sessions, store, environments, workspace, settings, missing, next, conversations);
    }

    /** 설정만 바꾼 새 상태. */
    public AppState withSettings(AppConfig next) {
        return new AppState(
                history, sessions, store, environments, workspace, Optional.of(next), missing, window, conversations);
    }

    /** 사라진 경로 집합만 바꾼 새 상태. */
    public AppState withMissing(Set<WorkspacePath> next) {
        return new AppState(history, sessions, store, environments, workspace, settings, next, window, conversations);
    }

    /** 가져온 대화만 바꾼 새 상태(#215). */
    public AppState withConversations(Map<ImportOrigin, List<PastConversation>> next) {
        return new AppState(history, sessions, store, environments, workspace, settings, missing, window, next);
    }

    /** 모든 origin 의 대화(트리 투영 입력). */
    public List<PastConversation> allConversations() {
        List<PastConversation> all = new ArrayList<>();
        for (List<PastConversation> list : conversations.values()) {
            all.addAll(list);
        }
        return List.copyOf(all);
    }
}
