package tanager.application.port.out;

import java.nio.file.Path;
import java.util.Objects;

/** 셸 통합 설치 결과(ADR-0014). 기동 명령은 이 값을 보고 스크립트를 주입할지 정한다. */
public sealed interface Integration {

    /** 스크립트가 {@code directory} 에 설치돼 있다. */
    record Installed(Path directory) implements Integration {
        public Installed {
            Objects.requireNonNull(directory, "directory");
        }
    }

    /** 셸 통합을 쓸 수 없다. {@code reason} 은 로그 · 알림에 보여 줄 이유. */
    record Unavailable(String reason) implements Integration {
        public Unavailable {
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("reason 이 비어 있다");
            }
        }
    }
}
