package tanager.domain.history;

import java.util.Objects;
import java.util.Optional;
import tanager.domain.shared.PaneId;

/**
 * 트리 투영의 세션 입력. history 가 session 패키지를 모르도록(컨텍스트 의존은 session → history 한 방향) 필요한 값만 담는다.
 * {@code cwd} 가 비어 있으면 아직 모르는 것(홈에서 띄워 OSC 가 오기 전 등)이다. 불변식: number ≥ 1 · cwd 가 있으면 그 환경이 env.
 */
public record SessionLeaf(
        PaneId pane,
        int number,
        String shellName,
        ShellEnvironment env,
        Optional<WorkspacePath> cwd,
        SessionStatus status) {

    public SessionLeaf {
        Objects.requireNonNull(pane, "pane");
        Objects.requireNonNull(shellName, "shellName");
        Objects.requireNonNull(env, "env");
        Objects.requireNonNull(cwd, "cwd");
        Objects.requireNonNull(status, "status");
        if (number < 1) {
            throw new IllegalArgumentException("세션 번호는 1 이상이어야 한다: " + number);
        }
        if (cwd.isPresent() && !cwd.get().env().equals(env)) {
            throw new IllegalArgumentException("cwd 의 환경이 세션 환경과 다르다: " + cwd.get() + " ≠ " + env);
        }
    }
}
