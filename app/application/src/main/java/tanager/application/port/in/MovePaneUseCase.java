package tanager.application.port.in;

import java.util.concurrent.CompletionStage;
import tanager.domain.layout.DropZone;
import tanager.domain.layout.LayoutError;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;
import tanager.domain.shared.TabId;
import tanager.domain.workspace.TabError;
import tanager.domain.workspace.Workspace;

/**
 * 창을 끌어다 놓는다(FR-LAY-10 · 12, #147 · #177). 바뀌면 {@code LayoutChanged} 가 한 번 나가고 줌은 풀린다. 옮기기는 창의 명세를 바꾸지 않아
 * 셸이 그대로 산다.
 */
public interface MovePaneUseCase {
    /**
     * {@code zone} 이 가운데면 {@code pane} 과 대상 창을 바꾸고, 변이면 대상 창의 그쪽에 붙인다. 옮긴 창에 포커스한다. 두 창이 같으면 {@link
     * LayoutError.SamePane}, 없는 창이면 {@link LayoutError.UnknownPane}.
     */
    CompletionStage<Result<Workspace, LayoutError>> move(PaneId pane, DropZone zone);

    /**
     * 창을 탭 {@code tab} 위에 놓았다(FR-LAY-12). 그 탭 포커스 창의 오른쪽에 붙이고 그 탭을 활성화해 옮긴 창에 포커스한다. 원래 탭의 유일한
     * 창이었으면 빈 탭은 닫힌다. 이미 그 탭이면 {@link TabError.SameTab}.
     */
    CompletionStage<Result<Workspace, TabError>> moveToTab(PaneId pane, TabId tab);

    /** 창 머리줄 우클릭 ▸ 새 탭으로 옮기기(FR-LAY-10). 맨 뒤의 새 탭으로 옮겨 활성화한다. 탭의 유일한 창이면 {@link TabError.SolePane}. */
    CompletionStage<Result<Workspace, TabError>> moveToNewTab(PaneId pane);
}
