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

    /**
     * 명령줄 인자로 옮길 때 안전하지 않은 문자가 있다 — 큰따옴표 · 제어 문자(0x00–0x1F, 0x7F), WSL 은 역슬래시도. {@code raw} 는 정규화 전
     * 입력이다(#107).
     */
    record UnsafeCharacter(String raw) implements PathError {
        public UnsafeCharacter {
            Objects.requireNonNull(raw, "raw");
        }
    }
}
