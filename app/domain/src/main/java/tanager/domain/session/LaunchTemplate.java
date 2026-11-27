package tanager.domain.session;

import java.util.List;
import java.util.Objects;

/** 셸을 띄울 실행 파일과 인자. 작업 디렉터리 · 셸 통합 주입은 기동 시점에 플랫폼이 덧붙인다. */
public record LaunchTemplate(String executable, List<String> args) {
    public LaunchTemplate {
        Objects.requireNonNull(executable, "executable");
        if (executable.isBlank()) {
            throw new IllegalArgumentException("실행 파일이 비어 있다");
        }
        args = List.copyOf(args);
    }
}
