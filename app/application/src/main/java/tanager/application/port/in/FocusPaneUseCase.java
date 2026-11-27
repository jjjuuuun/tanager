package tanager.application.port.in;

import java.util.concurrent.CompletionStage;
import tanager.domain.layout.Direction;
import tanager.domain.layout.LayoutError;
import tanager.domain.layout.Rect;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;
import tanager.domain.workspace.Workspace;

/** 창에 포커스한다(#88 · #147, FR-UI-03 · FR-LAY-06). 바뀌면 {@code LayoutChanged} 가 나간다. 다른 창으로 가면 줌이 풀린다. */
public interface FocusPaneUseCase {
    /** {@code pane} 에 포커스한다. 응답은 작업 공간, 없는 창이면 {@link LayoutError.UnknownPane}. 이미 포커스면 이벤트가 없다. */
    CompletionStage<Result<Workspace, LayoutError>> focus(PaneId pane);

    /**
     * 방향 포커스. {@code bounds} · {@code gap} 으로 그린 배치(UI 의 창 영역)에서 포커스 창의 {@code direction} 쪽 이웃에 포커스한다. 없으면
     * {@link LayoutError.NoNeighbor}.
     */
    CompletionStage<Result<Workspace, LayoutError>> focus(Direction direction, Rect bounds, int gap);
}
