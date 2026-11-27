package tanager.application.port.in;

import java.util.concurrent.CompletionStage;
import tanager.domain.layout.LayoutError;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;
import tanager.domain.workspace.Workspace;

/**
 * 창을 닫는다(#88). 그 창의 세션을 트리에서 빼고({@code SessionsChanged}) 레이아웃을 줄인다({@code LayoutChanged}). 마지막 창이면
 * 레이아웃은 그대로 두고 {@code LastPaneClosed} 를 낸다 — 앱을 끝내는 일은 UI 몫이다. 셸 프로세스 정리(스트림 close)도 UI 몫이다.
 */
public interface ClosePaneUseCase {
    /** {@code pane} 을 닫는다. 응답은 바뀐 작업 공간, 마지막 창이면 {@link LayoutError.LastPane}. */
    CompletionStage<Result<Workspace, LayoutError>> close(PaneId pane);
}
