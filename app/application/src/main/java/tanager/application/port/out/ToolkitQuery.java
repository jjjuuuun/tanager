package tanager.application.port.out;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import tanager.domain.history.WorkspacePath;

/**
 * {@link ToolkitProvider} 가 읽을 것(ADR-0019). {@code path} 와 {@code home} 은 그 환경의 경로이고, {@code host} 는 그 경로를 앱이
 * {@code java.nio} 로 읽을 경로로 바꾼다({@link HostFiles#hostPath}). 공급자는 OS 를 모른 채 이 함수로만 파일에 닿는다.
 *
 * @param path 고른 폴더 · 세션의 경로
 * @param home 그 환경의 사용자 홈. 모르면 empty — 사용자 범위는 읽지 않는다
 * @param host 환경 경로 → 앱이 읽을 경로(열 수 없으면 empty)
 */
public record ToolkitQuery(
        WorkspacePath path, Optional<WorkspacePath> home, Function<WorkspacePath, Optional<Path>> host) {
    public ToolkitQuery {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(host, "host");
    }
}
