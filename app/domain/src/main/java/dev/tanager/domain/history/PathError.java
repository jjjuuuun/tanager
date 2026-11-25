package dev.tanager.domain.history;

import java.util.Objects;

/** {@link WorkspacePath} 를 만들 수 없는 이유. */
public sealed interface PathError {

    /** 빈 문자열이거나 공백뿐이다. */
    record Blank() implements PathError {}

    /** 그 환경의 절대 경로 형식이 아니다(Windows: 드라이브 · UNC, WSL: '/' 시작). */
    record NotAbsolute(String raw) implements PathError {
        public NotAbsolute {
            Objects.requireNonNull(raw, "raw");
        }
    }
}
