package tanager.application.service;

import java.util.List;
import java.util.function.Function;
import tanager.application.port.in.AppEvent;
import tanager.application.state.AppState;
import tanager.application.state.Command;
import tanager.application.state.Transition;
import tanager.domain.shared.Result;
import tanager.domain.workspace.Workspace;

/**
 * 작업 공간 연산을 명령으로 감싼다(창 · 탭 공통, ADR-0012). 실제로 바뀌었을 때만 상태를 바꾸고 {@code LayoutChanged} 를 한 번 낸다. 실패 ·
 * 바뀐 게 없음이면 상태는 같은 인스턴스, 이벤트는 없다.
 */
final class WorkspaceChange {
    private WorkspaceChange() {}

    /** {@code operation} 을 현재 작업 공간에 적용하는 명령. */
    static <E> Command<Result<Workspace, E>> of(Function<Workspace, Result<Workspace, E>> operation) {
        return current -> operation
                .apply(current.workspace())
                .fold(next -> changed(current, next), error -> new Transition<>(current, List.of(), Result.err(error)));
    }

    /** {@code next} 로 바꾼 전이. 같으면 아무것도 바꾸지 않는다. */
    static <E> Transition<Result<Workspace, E>> changed(AppState current, Workspace next) {
        Result<Workspace, E> reply = Result.ok(next);
        if (next.equals(current.workspace())) {
            return new Transition<>(current, List.of(), reply);
        }
        return new Transition<>(current.withWorkspace(next), List.of(new AppEvent.LayoutChanged(next)), reply);
    }
}
