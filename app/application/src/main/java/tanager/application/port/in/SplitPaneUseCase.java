package tanager.application.port.in;

import java.util.concurrent.CompletionStage;
import tanager.domain.layout.LayoutError;
import tanager.domain.layout.Orientation;
import tanager.domain.shared.Result;
import tanager.domain.workspace.Workspace;

/**
 * 포커스 창을 나눈다(FR-LAY-01, #147). 키보드 · 마우스가 같은 유스케이스를 부른다(FR-LAY-09). 셸은 UI 가 새 창을 그린 뒤 그 명세로 띄운다.
 */
public interface SplitPaneUseCase {
    /**
     * 포커스 창 옆({@code orientation} — ROW 오른쪽, COLUMN 아래)에 새 창을 열고 그 창에 포커스한다. 새 창의 명세는 포커스 창의 셸을 따른다.
     *
     * <ul>
     *   <li>그 창에 세션이 있으면 그 세션의 프로파일 · 현재 경로(셸이 알린 경로, 알리기 전이면 시작 경로). 홈이면 경로는 비어 그 프로파일의 시작
     *       경로 → 홈.
     *   <li>세션이 없으면 그 창의 명세(프로파일 · 경로)를 그대로. 프로파일을 모르면(첫 창) 비어 기억한 기본 셸.
     * </ul>
     *
     * 응답은 바뀐 작업 공간이고 {@code LayoutChanged} 가 한 번 나간다. 줌은 풀린다.
     */
    CompletionStage<Result<Workspace, LayoutError>> split(Orientation orientation);
}
