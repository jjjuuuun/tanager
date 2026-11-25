package dev.tanager.domain.session;

import dev.tanager.domain.history.WorkspacePath;
import java.util.Objects;

/**
 * 셸을 띄울 작업 디렉터리. 홈은 띄우기 전에 실제 경로를 모를 수 있어(WSL) 따로 표현하고, 실제 경로로 바꾸는 일은 플랫폼이 기동
 * 명령을 만들 때 한다(ADR-0009).
 */
public sealed interface WorkingDir {

    /** 그 환경의 사용자 홈. */
    record Home() implements WorkingDir {}

    /** 정해진 절대 경로. */
    record At(WorkspacePath path) implements WorkingDir {
        public At {
            Objects.requireNonNull(path, "path");
        }
    }
}
