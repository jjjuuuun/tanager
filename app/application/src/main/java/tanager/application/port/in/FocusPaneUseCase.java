package tanager.application.port.in;

import java.util.concurrent.CompletionStage;
import tanager.domain.layout.LayoutError;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;
import tanager.domain.workspace.Workspace;

/** 창에 포커스한다(#88, FR-UI-03). 바뀌면 {@code LayoutChanged} 가 나간다. */
public interface FocusPaneUseCase {
    /** {@code pane} 에 포커스한다. 응답은 작업 공간, 없는 창이면 {@link LayoutError.UnknownPane}. */
    CompletionStage<Result<Workspace, LayoutError>> focus(PaneId pane);
}
