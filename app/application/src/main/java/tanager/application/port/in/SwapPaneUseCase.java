package tanager.application.port.in;

import java.util.concurrent.CompletionStage;
import tanager.domain.layout.Direction;
import tanager.domain.layout.LayoutError;
import tanager.domain.layout.Rect;
import tanager.domain.shared.Result;
import tanager.domain.workspace.Workspace;

/** 포커스 창을 이웃 창과 바꾼다(FR-LAY-03, #147). 바뀌면 {@code LayoutChanged} 가 한 번 나가고 줌은 풀린다. */
public interface SwapPaneUseCase {
    /**
     * {@code bounds} · {@code gap} 으로 그린 배치(UI 의 창 영역)에서 포커스 창의 {@code direction} 쪽 이웃과 자리를 바꾼다. 포커스는 옮겨 간 그
     * 창에 남는다. 이웃이 없으면 {@link LayoutError.NoNeighbor}.
     */
    CompletionStage<Result<Workspace, LayoutError>> swap(Direction direction, Rect bounds, int gap);
}
