package tanager.application.port.in;

import java.util.concurrent.CompletionStage;
import tanager.domain.layout.LayoutError;
import tanager.domain.shared.Result;
import tanager.domain.workspace.Workspace;

/** 포커스 창 줌(FR-LAY-05, #147). 다른 창 포커스 · 레이아웃 변경이 줌을 푸는 규칙은 {@link Workspace} 에 있다. */
public interface ZoomPaneUseCase {
    /** 줌하지 않았으면 포커스 창을 줌하고, 줌 상태면 푼다. 창이 하나면 성공하지만 바뀐 게 없어 이벤트도 없다. 바뀌면 {@code LayoutChanged}. */
    CompletionStage<Result<Workspace, LayoutError>> toggle();
}
