package tanager.application.port.out;

import java.nio.file.Path;
import tanager.domain.history.ConversationId;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.VisitSource;
import tanager.domain.session.ShellLine;
import tanager.domain.shared.Result;

/**
 * AI 도구의 이전 대화 기록을 읽는 공급자(SPI, FR-TK-09). {@code ServiceLoader} 로 여럿 찾는다. 읽기 전용이다 — 사용자 파일을 쓰지 않는다
 * (ADR-0020).
 */
public interface ConversationSource {

    /** 이 공급자가 만든 대화의 출처(예: {@code claude-code}). */
    VisitSource source();

    /** 이 환경의 기록을 읽을 수 있는지. */
    boolean supports(ShellEnvironment env);

    /**
     * 그 환경의 홈(호스트 경로 — WSL 이면 {@code \\wsl.localhost\<배포판>\home\<user>})에서 대화 기록을 읽는다. 블로킹이고 예외를 던지지
     * 않는다 — {@code StateActor} · EDT 에서 부르지 않는다. 파일 하나가 틀리면 건너뛰고 셀 뿐이다. 기록 폴더 형식 자체를 모르면 err — 부르는
     * 쪽이 기능을 끈다.
     */
    Result<ConversationScan, ConversationError> scan(ShellEnvironment env, Path hostHome);

    /** 그 대화를 이어서 하는 명령(예: {@code claude --resume <id>}). Enter 는 넣지 않는다. */
    ShellLine resume(ConversationId id);

    /** 그 폴더의 마지막 대화를 이어서 하는 명령(예: {@code claude --continue}). Enter 는 넣지 않는다. */
    ShellLine continueLatest();
}
