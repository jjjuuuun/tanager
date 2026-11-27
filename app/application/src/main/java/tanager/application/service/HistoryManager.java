package tanager.application.service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import tanager.application.port.in.AppEvent;
import tanager.application.port.in.ManageHistoryUseCase;
import tanager.application.state.AppState;
import tanager.application.state.StateActor;
import tanager.application.state.Transition;
import tanager.domain.history.HistoryError;
import tanager.domain.history.VisitedDirectory;
import tanager.domain.history.WorkHistory;
import tanager.domain.history.WorkspacePath;
import tanager.domain.shared.Result;

/**
 * 고정 · 별칭 · 숨김 · 잊기를 상태 액터 명령으로 처리한다(#83, ADR-0012). 바뀌면 {@code HistoryChanged} 한 번, 거부되면 상태 · 이벤트
 * 모두 그대로다. 저장 여부는 {@code HistorySaver} 가 저장소 상태로 정한다 — 읽기 전용이면 메모리에서만 바뀐다.
 *
 * <p>이력을 불러오는 중(Loading)에 바꾼 것은 불러온 이력이 덮는다. 불러오기 전에는 트리에 방금 방문한 경로만 있어 고칠 일이 드물다 —
 * 방문만 다시 적용한다(ADR-0013).
 */
public final class HistoryManager implements ManageHistoryUseCase {
    private final StateActor state;

    public HistoryManager(StateActor state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    @Override
    public CompletionStage<Result<VisitedDirectory, HistoryError>> pin(WorkspacePath path, boolean pinned) {
        Objects.requireNonNull(path, "path");
        return changed(path, history -> history.pin(path, pinned));
    }

    @Override
    public CompletionStage<Result<VisitedDirectory, HistoryError>> alias(WorkspacePath path, Optional<String> alias) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(alias, "alias");
        return changed(path, history -> history.alias(path, alias));
    }

    @Override
    public CompletionStage<Result<VisitedDirectory, HistoryError>> hide(WorkspacePath path, boolean hidden) {
        Objects.requireNonNull(path, "path");
        return changed(path, history -> history.hide(path, hidden));
    }

    @Override
    public CompletionStage<Result<VisitedDirectory, HistoryError>> forget(WorkspacePath path) {
        Objects.requireNonNull(path, "path");
        return state.submit(current -> {
            Optional<VisitedDirectory> removed = current.history().find(path);
            return apply(current, current.history().forget(path), history -> removed);
        });
    }

    // 바꾼 뒤의 항목으로 답한다
    private CompletionStage<Result<VisitedDirectory, HistoryError>> changed(
            WorkspacePath path, Function<WorkHistory, Result<WorkHistory, HistoryError>> change) {
        return state.submit(current -> apply(current, change.apply(current.history()), history -> history.find(path)));
    }

    private static Transition<Result<VisitedDirectory, HistoryError>> apply(
            AppState current,
            Result<WorkHistory, HistoryError> result,
            Function<WorkHistory, Optional<VisitedDirectory>> reply) {
        return result.fold(
                history -> new Transition<>(
                        current.withHistory(history),
                        List.of(new AppEvent.HistoryChanged(history)),
                        Result.<VisitedDirectory, HistoryError>ok(
                                reply.apply(history).orElseThrow(() -> new IllegalStateException("바꾼 항목이 없다")))),
                error -> new Transition<>(current, List.of(), Result.err(error)));
    }
}
