package tanager.application.port.out;

import java.time.Duration;
import java.util.Optional;
import tanager.domain.history.WorkspacePath;

/**
 * 작업 경로가 지금 있는 디렉터리인지 확인한다(#53, FR-WS-02). 복원(#179)과 사라진 경로 표시(#186)가 쓴다. {@link
 * PlatformModule#directories()} 가 준다.
 *
 * <p>블로킹이다 — {@code StateActor} · EDT 에서 부르지 않는다. WSL 경로는 Windows 쪽 {@code \\wsl.localhost\<배포판>\…} 로
 * 확인할 수 있어 그 배포판(VM)을 깨운다. 그래서 확인할지는 부르는 쪽이 정한다.
 */
@FunctionalInterface
public interface DirectoryProbe {
    /**
     * {@code path} 가 있는 디렉터리면 {@code true}, 없거나 디렉터리가 아니면 {@code false}, 모르면(시간 초과 · 접근 불가) empty.
     * 예외를 던지지 않는다. {@code timeout} 이 지나면 답을 기다리지 않는다.
     */
    Optional<Boolean> isDirectory(WorkspacePath path, Duration timeout);
}
