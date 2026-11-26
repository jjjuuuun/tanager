package dev.tanager.application.port.in;

import dev.tanager.application.port.out.TerminalSize;
import dev.tanager.domain.session.TerminalSession;
import dev.tanager.domain.shared.Result;

/** 셸 세션을 띄운다(FR-SES-02 · 04). 프로세스를 만드느라 블로킹되므로 EDT 에서 부르지 않는다. */
public interface StartSessionUseCase {

    /**
     * 요청한(없으면 기억한) 프로파일을 요청한(없으면 설정의 시작 경로 · 홈) 경로에서 띄운다. 띄울 셸을 정할 수 없으면 띄우지 않고
     * {@link StartFailure.ChoiceRequired} 다. 띄운 뒤 {@link StartRequest#rememberAsDefault()} 면 그 셸을 기본으로 저장한다.
     */
    Result<StartedSession, StartFailure> start(StartRequest request);

    /** 끝난 세션을 같은 프로파일로 마지막 경로에서 다시 띄운다. 설정은 다시 읽지도 저장하지도 않는다. */
    Result<StartedSession, StartFailure> restart(TerminalSession exited, TerminalSize size);
}
