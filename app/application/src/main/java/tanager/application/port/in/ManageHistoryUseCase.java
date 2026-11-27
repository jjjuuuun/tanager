package tanager.application.port.in;

import java.util.Optional;
import java.util.concurrent.CompletionStage;
import tanager.domain.history.HistoryError;
import tanager.domain.history.VisitedDirectory;
import tanager.domain.history.WorkspacePath;
import tanager.domain.shared.Result;

/**
 * 사용자가 이력 항목을 고정 · 별칭 · 숨김 · 잊기 한다(FR-HIS-04 · 07). 상태 액터에서 처리하고, 바뀌면 {@code HistoryChanged} 가 한 번
 * 나간다. 저장은 저장소를 쓸 수 있을 때만 한다 — 읽기 전용이면 메모리에서만 바뀐다(ADR-0013).
 *
 * <p>응답 단계는 액터 스레드에서 완료된다. 액터 스레드 안에서 기다리지 않는다(ADR-0012).
 */
public interface ManageHistoryUseCase {

    /** 고정하거나 푼다. 응답은 바뀐 항목. */
    CompletionStage<Result<VisitedDirectory, HistoryError>> pin(WorkspacePath path, boolean pinned);

    /** 별칭을 붙이거나({@code alias} 있음) 뗀다(비어 있음). 응답은 바뀐 항목. */
    CompletionStage<Result<VisitedDirectory, HistoryError>> alias(WorkspacePath path, Optional<String> alias);

    /** 숨기거나 다시 보인다. 응답은 바뀐 항목. */
    CompletionStage<Result<VisitedDirectory, HistoryError>> hide(WorkspacePath path, boolean hidden);

    /** 이력에서 지운다. 응답은 지운 항목. */
    CompletionStage<Result<VisitedDirectory, HistoryError>> forget(WorkspacePath path);
}
