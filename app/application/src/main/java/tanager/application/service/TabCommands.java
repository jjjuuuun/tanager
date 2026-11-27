package tanager.application.service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tanager.application.port.in.AppEvent;
import tanager.application.state.Command;
import tanager.application.state.Transition;
import tanager.domain.session.LiveSessions;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;
import tanager.domain.shared.TabId;
import tanager.domain.workspace.Tab;
import tanager.domain.workspace.TabError;
import tanager.domain.workspace.TabName;
import tanager.domain.workspace.Workspace;

/** 탭 명령(#177, FR-LAY-07 · 10 · 12, ADR-0012). 닫기 외에는 {@link WorkspaceChange} 규칙을 따른다. */
final class TabCommands {
    private TabCommands() {}

    /** 기억한 셸을 그 셸의 시작 경로에서 띄울 새 탭. */
    static Command<Result<Workspace, TabError>> create() {
        return WorkspaceChange.of(workspace ->
                Result.ok(workspace.newTab(Optional.empty(), Optional.empty()).workspace()));
    }

    /**
     * 탭을 닫고 그 탭 창들의 세션을 뺀다 — 한 전이 안에서 {@code LayoutChanged} 다음 {@code SessionsChanged}. 마지막 탭이면 작업 공간은 그대로
     * 두고 {@code LastPaneClosed} 만 낸다(마지막 창 닫기와 같다).
     */
    static Command<Result<Workspace, TabError>> close(TabId tab) {
        Objects.requireNonNull(tab, "tab");
        return current -> {
            Result<Workspace, TabError> closed = current.workspace().closeTab(tab);
            return switch (closed) {
                case Result.Err<Workspace, TabError>(TabError.LastTab _) ->
                    new Transition<>(current, List.of(new AppEvent.LastPaneClosed()), closed);
                case Result.Err<Workspace, TabError> _ -> new Transition<>(current, List.of(), closed);
                case Result.Ok<Workspace, TabError>(Workspace next) -> {
                    LiveSessions sessions = current.sessions();
                    for (PaneId pane : panesOf(current.workspace(), tab)) {
                        sessions = sessions.removed(pane);
                    }
                    yield new Transition<>(
                            current.withWorkspace(next).withSessions(sessions),
                            List.of(new AppEvent.LayoutChanged(next), new AppEvent.SessionsChanged(sessions)),
                            closed);
                }
            };
        };
    }

    /** 탭 이름을 바꾼다. 비면 자동 이름. 쓸 수 없는 이름이면 {@link TabError.InvalidName}. */
    static Command<Result<Workspace, TabError>> rename(TabId tab, Optional<String> name) {
        Objects.requireNonNull(tab, "tab");
        Objects.requireNonNull(name, "name");
        return WorkspaceChange.of(workspace -> parsed(name).flatMap(valid -> workspace.rename(tab, valid)));
    }

    static Command<Result<Workspace, TabError>> move(TabId tab, int toIndex) {
        Objects.requireNonNull(tab, "tab");
        return WorkspaceChange.of(workspace -> workspace.moveTab(tab, toIndex));
    }

    static Command<Result<Workspace, TabError>> select(TabId tab) {
        Objects.requireNonNull(tab, "tab");
        return WorkspaceChange.of(workspace -> workspace.select(tab));
    }

    static Command<Result<Workspace, TabError>> selectRelative(int delta) {
        return WorkspaceChange.of(workspace -> Result.ok(workspace.selectRelative(delta)));
    }

    static Command<Result<Workspace, TabError>> selectIndex(int index) {
        return WorkspaceChange.of(workspace -> workspace.selectIndex(index));
    }

    /** 창을 다른 탭으로 옮긴다(FR-LAY-12). 명세는 그대로다. */
    static Command<Result<Workspace, TabError>> moveToTab(PaneId pane, TabId tab) {
        Objects.requireNonNull(pane, "pane");
        Objects.requireNonNull(tab, "tab");
        return WorkspaceChange.of(workspace -> workspace.movePaneToTab(pane, tab));
    }

    /** 창을 새 탭으로 옮긴다(FR-LAY-10). 명세는 그대로다. */
    static Command<Result<Workspace, TabError>> moveToNewTab(PaneId pane) {
        Objects.requireNonNull(pane, "pane");
        return WorkspaceChange.of(workspace -> workspace.movePaneToNewTab(pane));
    }

    // 빈 값은 자동 이름(비어 있음)으로 그대로 간다
    private static Result<Optional<TabName>, TabError> parsed(Optional<String> name) {
        if (name.isEmpty()) {
            return Result.ok(Optional.empty());
        }
        return TabName.of(name.get()).map(Optional::of).mapError(TabError.InvalidName::new);
    }

    private static List<PaneId> panesOf(Workspace workspace, TabId tab) {
        for (Tab candidate : workspace.tabs()) {
            if (candidate.id().equals(tab)) {
                return candidate.root().panes();
            }
        }
        return List.of();
    }
}
