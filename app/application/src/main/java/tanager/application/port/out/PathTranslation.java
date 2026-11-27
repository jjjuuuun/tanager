package tanager.application.port.out;

import java.util.Optional;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;

/**
 * 한 환경의 경로를 다른 환경의 셸 cwd 로 바꾼다(#90, SDD §10.3). OS 마다 붙이는 자리가 달라 {@link PlatformModule#paths()} 가 준다. 순수
 * 계산이다 — 파일시스템을 보지 않는다.
 */
@FunctionalInterface
public interface PathTranslation {
    /** {@code from} 을 {@code to} 환경의 경로로. 같은 환경이면 그대로, 바꿀 수 없으면 empty. */
    Optional<WorkspacePath> translate(WorkspacePath from, ShellEnvironment to);
}
