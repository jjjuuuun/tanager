package tanager.domain.workspace;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tanager.domain.session.LiveSession;
import tanager.domain.session.LiveSessions;
import tanager.domain.shared.PaneId;

/** 복원 단위(SDD §5.4, FR-RES-02 · 03): 작업 공간과 앱 창 상태. 불변식은 {@link Workspace} 와 같다. */
public record WorkspaceSnapshot(Workspace workspace, WindowState window) {
    public WorkspaceSnapshot {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(window, "window");
    }

    /**
     * 저장할 값. 세션이 있는 창마다 명세의 프로파일 · 시작 경로를 바꾼다: 실제로 뜬 프로파일, 셸이 마지막으로 알린 cwd. 알린 경로가 없으면(홈에서 떠 OSC
     * 전) 시작 경로는 원래 명세 그대로다. 작업 공간에 없는 창의 세션은 무시한다. 순수.
     */
    public static WorkspaceSnapshot capture(Workspace workspace, LiveSessions sessions, WindowState window) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(sessions, "sessions");
        Map<PaneId, PaneSpec> specs = new HashMap<>(workspace.specs());
        for (LiveSession live : sessions.byPane().values()) {
            PaneSpec spec = specs.get(live.pane());
            if (spec == null) {
                continue;
            }
            specs.put(
                    live.pane(),
                    new PaneSpec(
                            live.pane(),
                            Optional.of(live.session().profile().id()),
                            live.lastReported().or(spec::dir)));
        }
        Workspace captured =
                new Workspace(workspace.tabs(), workspace.active(), specs, workspace.nextPane(), workspace.nextTab());
        return new WorkspaceSnapshot(captured, window);
    }
}
