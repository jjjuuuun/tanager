package tanager.application.port.in;

import java.util.concurrent.CompletionStage;
import tanager.domain.layout.DropZone;
import tanager.domain.layout.LayoutError;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;
import tanager.domain.workspace.Workspace;

/** 창을 끌어다 놓는다(FR-LAY-12, #147). 바뀌면 {@code LayoutChanged} 가 한 번 나가고 줌은 풀린다. */
public interface MovePaneUseCase {
    /**
     * {@code zone} 이 가운데면 {@code pane} 과 대상 창을 바꾸고, 변이면 대상 창의 그쪽에 붙인다. 옮긴 창에 포커스한다. 두 창이 같으면 {@link
     * LayoutError.SamePane}, 없는 창이면 {@link LayoutError.UnknownPane}.
     */
    CompletionStage<Result<Workspace, LayoutError>> move(PaneId pane, DropZone zone);
}
