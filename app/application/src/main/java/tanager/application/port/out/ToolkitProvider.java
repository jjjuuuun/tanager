package tanager.application.port.out;

import java.util.Optional;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.shared.Result;
import tanager.domain.toolkit.ProviderId;

/**
 * 특정 AI 도구의 구성을 읽는 공급자(SPI, SDD §7.4, ADR-0019). {@code ServiceLoader} 로 여럿 찾는다. 구현은 {@code
 * tanager.adapter.toolkit..} 에만 둔다(ArchUnit). 읽기 전용이다 — 파일을 쓰지 않는다. 비밀값은 읽는 즉시 마스킹한다.
 */
public interface ToolkitProvider {

    /** 공급자 식별자(예 {@code claude-code}). 이 공급자가 돌려주는 항목의 {@code provider} 도 이 값이어야 한다. */
    ProviderId id();

    /** 이 환경의 구성을 읽을 수 있는지. 순수 · 빠르다. */
    boolean supports(ShellEnvironment env);

    /**
     * 그 경로의 구성을 읽는다. 블로킹이고 예외를 던지지 않는다 — EDT · {@code StateActor} 에서 부르지 않는다. 파일 하나가 틀리면 그 항목 대신
     * 경고를 싣는다. 공급자 전체를 못 읽으면 err.
     */
    Result<ToolkitScan, ToolkitError> scan(ToolkitQuery query);

    /**
     * 지난 scan 의 입력이 그대로인지 파일 시각만으로 싸게 확인할 지문. 지난 scan 의 {@link ToolkitScan#stamp()} 와 같으면 다시 읽지 않는다. 블로킹이고
     * 예외를 던지지 않는다. 기본은 empty(캐시하지 않음).
     */
    default Optional<ScanStamp> stamp(ToolkitQuery query) {
        return Optional.empty();
    }
}
