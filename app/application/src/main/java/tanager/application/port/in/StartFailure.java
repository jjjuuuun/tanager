package tanager.application.port.in;

import java.util.List;
import java.util.Objects;
import tanager.application.port.out.LaunchError;
import tanager.domain.session.ProfileResolution;
import tanager.domain.session.SessionError;
import tanager.domain.session.SessionState;
import tanager.domain.session.TerminalSession;

/** 세션을 띄우지 못한 이유. */
public sealed interface StartFailure {

    /** 띄울 수 있는 셸이 하나도 없다. */
    record NoShellAvailable() implements StartFailure {}

    /** 요청이 세션 규칙에 맞지 않는다(다른 환경의 경로, 끝나지 않은 세션의 재시작 등). */
    record Rejected(SessionError error) implements StartFailure {
        public Rejected {
            Objects.requireNonNull(error, "error");
        }
    }

    /**
     * 프로세스를 띄우지 못했다.
     *
     * @param failed {@code Failed} 상태의 세션
     * @param error 기동 오류
     * @param notices 실패 전까지 모인 알림
     */
    record LaunchFailed(TerminalSession failed, LaunchError error, List<StartNotice> notices) implements StartFailure {
        public LaunchFailed {
            Objects.requireNonNull(failed, "failed");
            Objects.requireNonNull(error, "error");
            if (!(failed.state() instanceof SessionState.Failed)) {
                throw new IllegalArgumentException("기동 실패 세션은 Failed 여야 한다: " + failed.state());
            }
            notices = List.copyOf(notices);
        }
    }

    /**
     * 띄울 셸을 정하지 못해 아무것도 띄우지 않았다 — 사용자가 {@code choice.choices()} 에서 골라야 한다(FR-SES-02).
     *
     * @param choice 고를 목록과 사라진 기본 id
     * @param notices 그때까지 모인 알림(설정을 읽지 못함 · 설정 경고)
     */
    record ChoiceRequired(ProfileResolution.Choose choice, List<StartNotice> notices) implements StartFailure {
        public ChoiceRequired {
            Objects.requireNonNull(choice, "choice");
            notices = List.copyOf(notices);
        }
    }
}
