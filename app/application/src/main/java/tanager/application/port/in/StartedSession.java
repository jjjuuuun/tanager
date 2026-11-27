package tanager.application.port.in;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import tanager.application.port.out.TerminalStream;
import tanager.domain.session.SessionState;
import tanager.domain.session.TerminalSession;

/**
 * 띄운 세션. 받은 쪽이 {@code stream} 을 소유한다 — 쓰지 않고 버리더라도 반드시 {@link TerminalStream#close()} 해야 프로세스와
 * PTY 가 정리된다.
 *
 * @param session {@code Running} 상태의 세션
 * @param stream 셸 입출력. 아직 {@link TerminalStream#start()} 하지 않았다 — 구독을 붙인 뒤 부른다
 * @param exited 셸이 끝나면 {@code Exited} 상태의 세션으로 완료된다
 * @param notices 사용자에게 알릴 것(설정 문제 · 경로 · 기본 셸 저장 실패)
 */
public record StartedSession(
        TerminalSession session,
        TerminalStream stream,
        CompletionStage<TerminalSession> exited,
        List<StartNotice> notices) {
    public StartedSession {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(stream, "stream");
        Objects.requireNonNull(exited, "exited");
        if (!(session.state() instanceof SessionState.Running)) {
            throw new IllegalArgumentException("띄운 세션은 Running 이어야 한다: " + session.state());
        }
        notices = List.copyOf(notices);
    }
}
