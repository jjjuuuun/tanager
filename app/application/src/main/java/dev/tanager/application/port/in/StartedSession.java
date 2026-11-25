package dev.tanager.application.port.in;

import dev.tanager.application.port.out.TerminalStream;
import dev.tanager.domain.session.TerminalSession;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * 띄운 세션.
 *
 * @param session {@code Running} 상태의 세션
 * @param stream 셸 입출력. 아직 {@link TerminalStream#start()} 하지 않았다 — 구독을 붙인 뒤 부른다
 * @param exited 셸이 끝나면 {@code Exited} 상태의 세션으로 완료된다
 * @param notices 사용자에게 알릴 것(설정 문제 · 대체한 프로파일 · 경로)
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
        notices = List.copyOf(notices);
    }
}
