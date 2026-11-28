package tanager.application.port.in;

import java.util.Optional;
import java.util.concurrent.CompletionStage;
import tanager.application.port.out.OpenPlacement;
import tanager.domain.history.PastConversation;
import tanager.domain.history.VisitSource;
import tanager.domain.history.WorkspacePath;
import tanager.domain.shared.Result;
import tanager.domain.workspace.PaneSpec;

/**
 * 이전 대화를 새 창에서 이어 한다(FR-TK-09, #216). 창은 {@link OpenPaneUseCase#open} 처럼 그 환경의 기본 셸로 열고, 그 창의 첫 셸이 첫
 * 프롬프트에서 요청한 폴더를 알리면 대화 공급자의 명령을 한 번 입력 · 실행한다. 명령은 메모리에만 두고 저장 · 복원하지 않는다. 셸 탐지 · 설정
 * 읽기로 블로킹될 수 있어 EDT 에서 부르지 않는다.
 */
public interface ResumeConversationUseCase {

    /**
     * {@code conversation.cwd()} 에서 그 환경의 기본 셸로 새 창을 열고(자리: 비면 설정 {@code openPlacement}), 셸의 첫 프롬프트 뒤 그
     * 출처 공급자의 {@code resume(id)} 를 실행한다. 공급자가 없으면 {@link OpenError.UnknownSource}.
     */
    CompletionStage<Result<PaneSpec, OpenError>> resume(
            PastConversation conversation, Optional<OpenPlacement> placement);

    /** {@code folder} 에서 새 창을 열고 {@code source} 공급자의 {@code continueLatest()} 를 실행한다(폴더 메뉴 "여기서 claude --continue"). */
    CompletionStage<Result<PaneSpec, OpenError>> continueLatest(
            WorkspacePath folder, VisitSource source, Optional<OpenPlacement> placement);
}
